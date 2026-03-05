package leyline.frontdoor

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import leyline.debug.FdDebugCollector
import leyline.protocol.ClientFrameDecoder
import leyline.protocol.FdEnvelope
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * Replay-based Front Door stub.
 *
 * Loads a captured FD session (`fd-frames.jsonl` from proxy mode) and
 * replays server responses by matching incoming CmdType to recorded
 * S→C responses. Dynamic fields (sessionId, matchId, host:port) are
 * patched in the JSON before sending.
 *
 * Falls back to empty `{}` for unmatched CmdTypes.
 *
 * Usage:
 * - Capture: run `just serve-proxy`, connect client, play through lobby → match start
 * - Golden file: `recordings/<session>/capture/fd-frames.jsonl`
 * - Copy to: `src/main/resources/fd-golden/fd-frames.jsonl` (or pass via config)
 * - Stub mode uses this instead of hand-crafted responses
 */
class FrontDoorReplayStub(
    goldenFile: File,
    private val matchDoorHost: String = "localhost",
    private val matchDoorPort: Int = 30003,
) : ChannelInboundHandlerAdapter() {

    private val log = LoggerFactory.getLogger(FrontDoorReplayStub::class.java)

    private val jsonParser = Json { ignoreUnknownKeys = true }

    /** Recorded S→C responses keyed by the CmdType of the C→S request they followed. */
    private val responseMap: Map<Int, List<GoldenResponse>>

    /** Queue of S→C push notifications (MatchCreated etc.) to send after startup. */
    private val pushQueue: MutableList<GoldenResponse>

    /** Track which response index we've sent per CmdType (for multi-response CmdTypes). */
    private val responseIndex = mutableMapOf<Int, Int>()

    private var matchCreatedSent = false

    init {
        val (responses, pushes) = loadGoldenFrames(goldenFile)
        responseMap = responses
        pushQueue = pushes.toMutableList()
        log.info(
            "FD Replay: loaded {} CmdType responses, {} push notifications from {}",
            responseMap.size,
            pushQueue.size,
            goldenFile.name,
        )
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        log.info("FD Replay: client connected from {}", ctx.channel().remoteAddress())
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is ByteBuf) {
            ReferenceCountUtil.release(msg)
            return
        }
        try {
            val bytes = ByteArray(msg.readableBytes())
            msg.readBytes(bytes)

            if (bytes.size < ClientFrameDecoder.HEADER_SIZE) return
            val frameType = bytes[1]

            // Handle control frames
            if (frameType == ClientFrameDecoder.TYPE_CTRL_INIT) {
                val ack = bytes.copyOf()
                ack[1] = ClientFrameDecoder.TYPE_CTRL_ACK
                val buf = ctx.alloc().buffer(ack.size)
                buf.writeBytes(ack)
                ctx.writeAndFlush(buf)
                return
            }
            if (frameType == ClientFrameDecoder.TYPE_CTRL_ACK) return

            val payload = if (bytes.size > ClientFrameDecoder.HEADER_SIZE) {
                bytes.copyOfRange(ClientFrameDecoder.HEADER_SIZE, bytes.size)
            } else {
                return
            }

            val decoded = FdEnvelope.decode(payload)
            val cmdType = decoded.cmdType
            val cmdName = cmdType?.let { FdEnvelope.cmdTypeName(it) }
            FdDebugCollector.record("C2S", decoded)

            log.info("FD Replay: C→S cmd={} ({}) txId={}", cmdName, cmdType, decoded.transactionId)

            if (cmdType == null) {
                // Response envelope from client — unusual, ignore
                return
            }

            // Auth (CmdType=0) always needs a fresh SessionId — never replay stale
            if (cmdType == 0) {
                val sessionId = UUID.randomUUID().toString()
                val authJson = """{"SessionId":"$sessionId","Attached":true}"""
                log.info("FD Replay: S→C auth response (SessionId={})", sessionId)
                sendResponse(ctx, decoded.transactionId ?: UUID.randomUUID().toString(), authJson)
                return
            }

            // Look up recorded response for this CmdType
            val responses = responseMap[cmdType]
            if (responses != null) {
                val idx = responseIndex.getOrDefault(cmdType, 0)
                val golden = responses[idx.coerceAtMost(responses.size - 1)]
                responseIndex[cmdType] = idx + 1

                val txId = decoded.transactionId ?: UUID.randomUUID().toString()

                if (golden.jsonPayload == null) {
                    // Protobuf-only response (e.g. GetFormats/GetSets) — JSONL doesn't
                    // capture raw proto bytes, so send empty ack. Use FrontDoorHandler
                    // (not replay) for full offline support of protobuf CmdTypes.
                    log.warn("FD Replay: S→C proto-only for {} ({}) — sending empty ack", cmdName, cmdType)
                    sendResponse(ctx, txId, "{}")
                } else {
                    val patchedJson = patchDynamicFields(golden.jsonPayload)
                    log.info(
                        "FD Replay: S→C replay for {} ({}), json={}",
                        cmdName,
                        cmdType,
                        patchedJson.take(80),
                    )
                    sendResponse(ctx, txId, patchedJson)
                }
            } else {
                // No recorded response — send empty
                log.info("FD Replay: S→C empty for {} ({})", cmdName, cmdType)
                sendResponse(ctx, decoded.transactionId ?: UUID.randomUUID().toString(), "{}")
            }

            // After responding to Event_AiBotMatch (612) or once enough
            // startup messages are done, push MatchCreated
            if (cmdType == 612 && !matchCreatedSent) {
                pushMatchCreated(ctx)
            }
        } finally {
            msg.release()
        }
    }

    private fun sendResponse(ctx: ChannelHandlerContext, transactionId: String, json: String) {
        val envelope = FdEnvelope.encodeResponse(transactionId, json)
        val header = FdEnvelope.buildOutgoingHeader(envelope.size)

        FdDebugCollector.record(
            "S2C",
            FdEnvelope.FdMessage(
                cmdType = null,
                transactionId = transactionId,
                jsonPayload = json,
                envelopeType = FdEnvelope.EnvelopeType.RESPONSE,
            ),
        )

        val buf = ctx.alloc().buffer(header.size + envelope.size)
        buf.writeBytes(header)
        buf.writeBytes(envelope)
        ctx.writeAndFlush(buf)
    }

    private fun pushMatchCreated(ctx: ChannelHandlerContext) {
        matchCreatedSent = true
        val matchId = UUID.randomUUID().toString()

        // Try to use recorded push, otherwise build minimal one
        val goldenPush = pushQueue.firstOrNull { it.cmdType == 600 }
        val json = if (goldenPush?.jsonPayload != null) {
            patchDynamicFields(goldenPush.jsonPayload)
        } else {
            FdEnvelope.buildMatchCreatedJson(matchId, matchDoorHost, matchDoorPort)
        }

        log.info("FD Replay: pushing MatchCreated matchId={}", matchId)
        // Client parses ALL S→C frames as Response — use fresh txId as push
        val txId = UUID.randomUUID().toString()
        sendResponse(ctx, txId, json)
    }

    /** Patch dynamic fields in recorded JSON. */
    private fun patchDynamicFields(json: String): String {
        var patched = json
        // Patch match endpoint
        patched = patched.replace(
            Regex(""""MatchEndpointHost"\s*:\s*"[^"]*""""),
            """"MatchEndpointHost":"$matchDoorHost"""",
        )
        patched = patched.replace(
            Regex(""""MatchEndpointPort"\s*:\s*\d+"""),
            """"MatchEndpointPort":$matchDoorPort""",
        )
        // Patch matchId to unique value
        patched = patched.replace(
            Regex(""""MatchId"\s*:\s*"[^"]*""""),
            """"MatchId":"${UUID.randomUUID()}"""",
        )
        return patched
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error("FD Replay error: {}", cause.message, cause)
        ctx.close()
    }

    // --- Golden frame loading ---

    @Serializable
    private data class GoldenFrame(
        val seq: Long = 0,
        val dir: String = "",
        val cmdType: Int? = null,
        val cmdTypeName: String? = null,
        val transactionId: String? = null,
        val envelopeType: String? = null,
        val jsonPayload: String? = null,
    )

    private data class GoldenResponse(
        val cmdType: Int?,
        val jsonPayload: String?,
    )

    /**
     * Load fd-frames.jsonl and build response lookup.
     *
     * Strategy: pair each S2C response with the preceding C2S request's CmdType.
     * Push notifications (S2C Cmd with CmdType) go into the push queue.
     */
    private fun loadGoldenFrames(file: File): Pair<Map<Int, List<GoldenResponse>>, List<GoldenResponse>> {
        val responsesByCmd = mutableMapOf<Int, MutableList<GoldenResponse>>()
        val pushes = mutableListOf<GoldenResponse>()

        val frames = file.readLines()
            .filter { it.isNotBlank() }
            .map { jsonParser.decodeFromString<GoldenFrame>(it) }

        var lastC2sCmdType: Int? = null

        for (frame in frames) {
            if (frame.dir == "C2S") {
                lastC2sCmdType = frame.cmdType
            } else if (frame.dir == "S2C") {
                if (frame.envelopeType == "CMD" && frame.cmdType != null) {
                    // Push notification
                    pushes.add(GoldenResponse(frame.cmdType, frame.jsonPayload))
                } else if (lastC2sCmdType != null) {
                    // Response to the last C→S request
                    responsesByCmd.getOrPut(lastC2sCmdType) { mutableListOf() }
                        .add(GoldenResponse(lastC2sCmdType, frame.jsonPayload))
                }
            }
        }

        return responsesByCmd to pushes
    }
}
