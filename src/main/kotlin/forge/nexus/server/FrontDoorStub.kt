package forge.nexus.server

import forge.nexus.protocol.ClientFrameDecoder
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Minimal Front Door stub (port 30010).
 *
 * The real client Front Door uses a **protobuf envelope wrapping JSON strings** —
 * NOT the same [ClientToMatchServiceMessage] protobuf used by the Match Door.
 * The envelope schema is not in `messages.proto`, so we handle raw bytes.
 *
 * Flow:
 * 1. Client sends auth (JWT + ClientVersion as JSON inside protobuf envelope)
 * 2. Stub echoes header (ack) + sends auth response JSON
 * 3. Client sends state request (`{"RequestedType":"None"}`)
 * 4. Stub sends minimal state response + MatchCreated push
 * 5. Client opens TLS connection to Match Door
 */
class FrontDoorStub(
    private val matchDoorHost: String = "localhost",
    private val matchDoorPort: Int = 30003,
) : ChannelInboundHandlerAdapter() {

    private val log = LoggerFactory.getLogger(FrontDoorStub::class.java)

    private enum class State { AWAITING_AUTH, AWAITING_STATE_REQ, LOBBY, DONE }

    private var state = State.AWAITING_AUTH

    override fun channelActive(ctx: ChannelHandlerContext) {
        log.info("Front Door: client connected from {}", ctx.channel().remoteAddress())
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is ByteBuf) return
        try {
            val bytes = ByteArray(msg.readableBytes())
            msg.readBytes(bytes)

            // ClientFrameDecoder gives us header + payload as one ByteBuf
            if (bytes.size < ClientFrameDecoder.Companion.HEADER_SIZE) return

            val frameType = bytes[1]

            // Handle control frames: echo full frame (header + nonce) with type flipped
            if (frameType == ClientFrameDecoder.Companion.TYPE_CTRL_INIT) {
                log.debug("Front Door: CTRL_INIT received, sending ACK")
                sendCtrlAck(ctx, bytes)
                return
            }
            if (frameType == ClientFrameDecoder.Companion.TYPE_CTRL_ACK) {
                log.debug("Front Door: CTRL_ACK received (ignored)")
                return
            }

            val payload = if (bytes.size > ClientFrameDecoder.Companion.HEADER_SIZE) {
                bytes.copyOfRange(ClientFrameDecoder.Companion.HEADER_SIZE, bytes.size)
            } else {
                null
            }

            if (payload == null) {
                log.debug("Front Door: header-only message (ack/heartbeat)")
                return
            }

            val json = extractJson(payload)
            val transactionId = extractUuid(payload)

            log.info("Front Door: state={} json={} txId={}", state, json?.take(80), transactionId)

            when (state) {
                State.AWAITING_AUTH -> {
                    if (json != null && ("ClientVersion" in json || "Token" in json)) {
                        log.info("Front Door: auth request received, sending session")
                        val sessionId = UUID.randomUUID().toString()
                        sendJsonResponse(ctx, transactionId, """{"SessionId":"$sessionId","Attached":true}""")
                        state = State.AWAITING_STATE_REQ
                    }
                }

                State.AWAITING_STATE_REQ -> {
                    if (json != null && "RequestedType" in json) {
                        log.info("Front Door: state request received, sending minimal state")
                        sendJsonResponse(ctx, transactionId, """{}""")
                        state = State.LOBBY
                    }
                }

                State.LOBBY -> {
                    if (json == null) return
                    when {
                        "GraphId" in json -> handleGraphRequest(ctx, transactionId, json)
                        "AIBotMatch" in json || "PlayQueue" in json || "Play" in json ->
                            sendMatchCreated(ctx)
                        else -> {
                            // Respond with empty JSON to keep the client happy
                            log.info("Front Door: responding with empty to: {}", json.take(60))
                            sendJsonResponse(ctx, transactionId, """{}""")
                        }
                    }
                }

                State.DONE -> {
                    if (json != null && "GraphId" in json) {
                        handleGraphRequest(ctx, transactionId, json)
                    } else {
                        log.debug("Front Door: ignoring message in state DONE")
                    }
                }
            }
        } finally {
            msg.release()
        }
    }

    // --- outgoing message construction ---

    /** Respond to a CTRL_INIT by echoing the full frame (header + nonce) with type flipped. */
    private fun sendCtrlAck(ctx: ChannelHandlerContext, initFrame: ByteArray) {
        val ack = initFrame.copyOf()
        ack[1] = ClientFrameDecoder.Companion.TYPE_CTRL_ACK
        val buf = ctx.alloc().buffer(ack.size)
        buf.writeBytes(ack)
        ctx.writeAndFlush(buf)
    }

    private fun sendJsonResponse(ctx: ChannelHandlerContext, transactionId: String?, json: String) {
        val txId = transactionId ?: UUID.randomUUID().toString()
        val envelope = buildEnvelope(txId, json)
        val header = buildOutgoingHeader(envelope.size)

        val buf = ctx.alloc().buffer(header.size + envelope.size)
        buf.writeBytes(header)
        buf.writeBytes(envelope)
        ctx.writeAndFlush(buf)
    }

    private fun sendMatchCreated(ctx: ChannelHandlerContext) {
        val matchId = UUID.randomUUID().toString()
        val json = buildMatchCreatedJson(matchId)
        log.info("Front Door: pushing MatchCreated matchId={}", matchId)
        sendJsonResponse(ctx, UUID.randomUUID().toString(), json)
        state = State.DONE
    }

    private fun handleGraphRequest(ctx: ChannelHandlerContext, transactionId: String?, json: String) {
        val graphId = GRAPH_ID_PATTERN.find(json)?.groupValues?.get(1) ?: "unknown"
        log.info("Front Door: GraphGetGraphState graphId={}", graphId)
        val response = GRAPH_RESPONSES[graphId] ?: GRAPH_DEFAULT
        sendJsonResponse(ctx, transactionId, response)
    }

    // --- wire format helpers ---

    /**
     * Build a protobuf envelope wrapping a transactionId + JSON payload.
     *
     * S→C layout:
     * - Field [FIELD_TRANSACTION_ID_SC] (string): transactionId UUID
     * - Field [FIELD_PAYLOAD_SC] (string): JSON response
     */
    private fun buildEnvelope(transactionId: String, json: String): ByteArray {
        val buf = ByteArrayOutputStream()
        writeProtoString(buf, FIELD_TRANSACTION_ID_SC, transactionId)
        writeProtoString(buf, FIELD_PAYLOAD_SC, json)
        return buf.toByteArray()
    }

    /** Build a 6-byte outgoing header (version + type + LE payload length). */
    private fun buildOutgoingHeader(payloadLength: Int): ByteArray {
        val h = ByteArray(ClientFrameDecoder.Companion.HEADER_SIZE)
        h[0] = ClientFrameDecoder.Companion.VERSION
        h[1] = ClientFrameDecoder.Companion.TYPE_DATA_FD
        // Bytes 2-5: payload length (little-endian)
        h[2] = (payloadLength and 0xFF).toByte()
        h[3] = ((payloadLength shr 8) and 0xFF).toByte()
        h[4] = ((payloadLength shr 16) and 0xFF).toByte()
        h[5] = ((payloadLength shr 24) and 0xFF).toByte()
        return h
    }

    private fun buildMatchCreatedJson(matchId: String): String {
        // Minimum fields the client needs for match connection
        return """{"Type":"MatchCreated","MatchInfoV3":{""" +
            """"MatchEndpointHost":"$matchDoorHost",""" +
            """"MatchEndpointPort":$matchDoorPort,""" +
            """"MatchId":"$matchId",""" +
            """"McFabricId":"wzmc://forge/$matchId",""" +
            """"EventId":"AIBotMatch",""" +
            """"MatchTypeInternal":1,""" +
            """"YourSeat":1,""" +
            """"PlayerInfos":[""" +
            """{"ScreenName":"ForgePlayer","SeatId":1,"TeamId":1,"CosmeticsSelection":{"Avatar":{"Type":"Avatar","Id":"Avatar_Basic_Adventurer"},"Emotes":[]}},""" +
            """{"ScreenName":"Sparky","SeatId":2,"TeamId":2,"CosmeticsSelection":{"Avatar":{"Type":"Avatar","Id":"Avatar_Basic_Sparky"},"Emotes":[]}}""" +
            """],"MatchType":"Familiar"}}"""
    }

    // --- payload extraction ---

    /** Extract the first JSON object from raw payload bytes. */
    private fun extractJson(payload: ByteArray): String? {
        val text = payload.toString(Charsets.UTF_8)
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** Extract the first UUID from raw payload bytes. */
    private fun extractUuid(payload: ByteArray): String? {
        val text = payload.toString(Charsets.UTF_8)
        return UUID_PATTERN.find(text)?.value
    }

    // --- protobuf wire format primitives ---

    private fun writeProtoString(out: ByteArrayOutputStream, fieldNumber: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeTag(out, fieldNumber, WIRE_TYPE_LENGTH_DELIMITED)
        writeVarint(out, bytes.size)
        out.write(bytes)
    }

    private fun writeTag(out: ByteArrayOutputStream, fieldNumber: Int, wireType: Int) {
        writeVarint(out, (fieldNumber shl 3) or wireType)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error("Front Door error: {}", cause.message, cause)
        ctx.close()
    }

    companion object {
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val GRAPH_ID_PATTERN = Regex(""""GraphId"\s*:\s*"([^"]+)"""")
        private const val WIRE_TYPE_LENGTH_DELIMITED = 2

        // Graph state responses (NPE/tutorial completed flags).
        private val GRAPH_RESPONSES = mapOf(
            "NPE_Tutorial" to """{"NodeStates":{"PlayFamiliar1":{"Status":"Completed"},"PlayFamiliar2":{"Status":"Completed"},"PlayFamiliar3":{"Status":"Completed"},"PlayFamiliar4":{"Status":"Completed"},"PlayFamiliar5":{"Status":"Completed"},"SkipTutorial":{"Status":"Completed"},"Payout":{"Status":"Completed"},"Reset":{"Status":"Available"}},"MilestoneStates":{"TutorialComplete":true}}""",
            "NewPlayerExperience" to """{"NodeStates":{"UnlockPlayModes":{"Status":"Available"},"MigrateFromNPEv1":{"Status":"Completed"},"CheckForForceCloseSDD":{"Status":"Completed"},"SkipNPEV2":{"Status":"Completed"},"SparkyMatch":{"Status":"Available","ProgressNodeState":{},"ProgressionHistoryStateDataState":{}},"SparkyMatchNonRebalanced":{"Status":"Available","ProgressNodeState":{}},"ColorChallengeAnyOneTrackComplete":{"Status":"Available","ProgressionHistoryStateDataState":{}},"OpenDualColorPreconEvent":{"Status":"Completed"},"DualColorPreconEventWonEnough":{"Status":"Completed"},"SparkRankTier3":{"Status":"Completed"},"SparkRankTier2":{"Status":"Completed"},"SparkRankTier1":{"Status":"Completed"},"PlayedThroughSparkRank":{"Status":"Completed"},"CheckForceCompleteJumpInUnlock":{"Status":"Completed"},"JumpIn_Unlocked":{"Status":"Completed"},"SkipSparkRank":{"Status":"Completed"},"GraduateSparkRank":{"Status":"Completed"},"ColorChallengeAnyOneTrackComplete_Rewards":{"Status":"Completed"},"ColorChallengeComplete_Rewards":{"Status":"Completed"},"SparkyChallengeEventWonEnough_Rewards":{"Status":"Completed"},"DualColorPreconEventWonEnough_Rewards":{"Status":"Completed"},"GraduateSparkRank_Rewards":{"Status":"Completed"},"SparkRankTier1_Rewards":{"Status":"Completed"},"SparkRankTier2_Rewards":{"Status":"Completed"},"SparkRankTier3_Rewards":{"Status":"Completed"},"SparkyMatch_Rewards":{"Status":"Completed"},"JumpIn_3_Runs_Rewards":{"Status":"Completed"},"JumpIn_5_Runs_Rewards":{"Status":"Completed"},"ColorChallengeComplete":{"Status":"Completed"},"CheckForceCompleteQuickDraftUnlock":{"Status":"Completed"},"UnlockQuickDraftExitNode":{"Status":"Completed"},"Played3QuickDraftEvents":{"Status":"Completed"},"QuickDraft_Quest_Rewards":{"Status":"Completed"},"CheckForceCompleteAlchemyPlayUnlock":{"Status":"Completed"},"UnlockAlchemyPlayExitNode":{"Status":"Completed"}},"MilestoneStates":{"OpenSparkyDeckDuel":false,"CloseSparkyDeckDuel":false,"OpenDualColorPreconEvent":true,"CompletedDualColorPreconEvent":false,"OpenSparkQueue":true,"SparkRankTier3":true,"SparkRankTier2":true,"OpenAlchemyPlayQueue":true,"SparkRankTier1":true,"PlayedThroughSparkRank":true,"SkippedSparkRank":false,"OpenJumpInEvent":true,"GraduateSparkRank":true,"Played3QuickDraftEvents":true,"NPE_Completed":true,"SkippedNPEV2FromNPEV1Migration":true,"OpenQuickDraftAndSealedEvents":true,"OpenHistoricBrawlQueue":true,"OpenAlchemyRankQueue":true}}""",
            "ColorChallenge" to """{"NodeStates":{"UnlockPlayMode":{"Status":"Completed"},"ChallengeComplete":{"Status":"Completed"}},"MilestoneStates":{"AnyOneTrackComplete":false,"ColorChallengeComplete":true,"NPE_V1_Unlocked_Ranked_PVP":true,"NPE_V1_Got_EPP_Deck_RG":true,"NPE_V1_Got_EPP_Deck_WU":true,"NPE_V1_Got_EPP_Deck_BR":true,"NPE_V1_Got_EPP_Deck_GW":true,"NPE_V1_Got_EPP_Deck_UB":true,"NPE_V1_Got_EPP_Deck_5_Enemy_Duals":true}}""",
        )
        private const val GRAPH_DEFAULT = """{"NodeStates":{},"MilestoneStates":{}}"""

        // S→C envelope layout:
        //   field 1 (string) = transactionId UUID
        //   field 3 (string/bytes) = JSON response
        //   field 5 (varint) = status (optional)
        const val FIELD_TRANSACTION_ID_SC = 1
        const val FIELD_PAYLOAD_SC = 3
    }
}
