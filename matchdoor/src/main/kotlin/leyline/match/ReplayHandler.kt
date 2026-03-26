package leyline.match

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import java.io.File

/**
 * Match Door handler that replays a recorded session instead of running Forge.
 *
 * Loads captured S→C payloads from a proxy session, categorizes them
 * (auth, room state, GRE), and serves them back in order as the client
 * sends requests. Patches matchId/clientId/seatId so the current
 * session's identifiers match the recording.
 *
 * Why: lets you iterate on client-facing protocol work (framing, field
 * shapes, annotation ordering) without waiting for Forge engine startup
 * or needing a specific board state. Swap with MatchHandler at bootstrap.
 *
 * Usage: `just serve-replay` or `--replay <payloads-dir>`
 */
class ReplayHandler(
    private val payloadDir: File,
) : SimpleChannelInboundHandler<ClientToMatchServiceMessage>(),
    ReplayController {

    private val log = LoggerFactory.getLogger(ReplayHandler::class.java)

    private var seatId = 0
    private var clientId = ""
    private var matchId = ""

    private val authResponses: MutableList<CapturedPayload>
    private val matchRoomStates: MutableList<CapturedPayload>
    private val greEvents: MutableList<CapturedPayload>
    private val uncategorized: MutableList<CapturedPayload>

    // -- ReplayController state --
    private var greFrameIndex: List<ReplayController.FrameInfo> = emptyList()
    private var grePosition = 0

    @Volatile private var pendingCtx: ChannelHandlerContext? = null

    init {
        val seat1Payloads = File(payloadDir, "capture/seat-1/md-payloads")
        val framesDir = File(payloadDir, "capture/frames")
        val engineDir = File(payloadDir, "engine")

        // Detect recording format and whether files have 6-byte frame headers.
        val stripHeader: Boolean
        val allFiles: List<File>

        fun matchDataFiles(dir: File) = dir.listFiles()
            ?.filter { it.name.contains("MD_S-C_MATCH_DATA") }
            ?.sortedBy { it.name }
            ?: emptyList()

        when {
            // Decoded per-seat payloads (no header) — current proxy format
            seat1Payloads.isDirectory -> {
                log.info("Replay: detected seat-1 payloads in {}", seat1Payloads)
                allFiles = matchDataFiles(seat1Payloads)
                stripHeader = false
            }
            // Raw proxy frames (6-byte header) — older captures
            framesDir.isDirectory -> {
                log.info("Replay: detected raw frames in {}", framesDir)
                allFiles = matchDataFiles(framesDir)
                stripHeader = true
            }
            // Engine format
            engineDir.isDirectory -> {
                log.info("Replay: detected engine format in {}", engineDir)
                allFiles = engineDir.listFiles()
                    ?.filter { it.extension == "bin" && !it.name.contains("AuthResp") && !it.name.contains("RoomState") }
                    ?.sortedBy { it.name }
                    ?: emptyList()
                stripHeader = false
            }
            // Legacy: direct payloads dir
            else -> {
                log.info("Replay: loading from {}", payloadDir)
                allFiles = matchDataFiles(payloadDir)
                stripHeader = false
            }
        }

        val auths = mutableListOf<CapturedPayload>()
        val rooms = mutableListOf<CapturedPayload>()
        val gres = mutableListOf<CapturedPayload>()
        val other = mutableListOf<CapturedPayload>()

        val headerSize = 6

        for (file in allFiles) {
            val rawBytes = file.readBytes()
            val protoBytes = if (stripHeader && rawBytes.size > headerSize) {
                rawBytes.copyOfRange(headerSize, rawBytes.size)
            } else {
                rawBytes
            }
            val parsed = try {
                MatchServiceToClientMessage.parseFrom(protoBytes)
            } catch (_: Exception) {
                null
            }
            val cp = CapturedPayload(file.name, protoBytes, parsed)

            when {
                parsed == null -> {
                    log.debug("Replay: skipping unparseable {}", file.name)
                    other.add(cp)
                }
                parsed.hasAuthenticateResponse() -> auths.add(cp)
                parsed.hasMatchGameRoomStateChangedEvent() -> rooms.add(cp)
                parsed.hasGreToClientEvent() -> gres.add(cp)
                else -> other.add(cp)
            }
        }

        // Engine format: load auth and room state files separately
        if (engineDir.isDirectory) {
            val authFiles = engineDir.listFiles()
                ?.filter { it.name.contains("AuthResp") && it.extension == "bin" }
                ?.sortedBy { it.name }
                ?: emptyList()
            for (file in authFiles) {
                val bytes = file.readBytes()
                val parsed = try {
                    MatchServiceToClientMessage.parseFrom(bytes)
                } catch (_: Exception) {
                    null
                }
                if (parsed != null) auths.add(CapturedPayload(file.name, bytes, parsed))
            }
            val roomFiles = engineDir.listFiles()
                ?.filter { it.name.contains("RoomState") && it.extension == "bin" }
                ?.sortedBy { it.name }
                ?: emptyList()
            for (file in roomFiles) {
                val bytes = file.readBytes()
                val parsed = try {
                    MatchServiceToClientMessage.parseFrom(bytes)
                } catch (_: Exception) {
                    null
                }
                if (parsed != null) rooms.add(CapturedPayload(file.name, bytes, parsed))
            }
        }

        authResponses = auths
        matchRoomStates = rooms
        greEvents = gres
        uncategorized = other

        log.info(
            "Replay: loaded {} payloads (auth={}, room={}, gre={}, other={})",
            allFiles.size,
            auths.size,
            rooms.size,
            gres.size,
            other.size,
        )

        greFrameIndex = gres.mapIndexed { i, cp ->
            val greType = cp.parsed?.greToClientEvent
                ?.greToClientMessagesList
                ?.firstOrNull()?.type?.name
                ?: "Unknown"
            ReplayController.FrameInfo(
                index = i,
                fileName = cp.fileName,
                greType = greType,
                category = "gre",
            )
        }
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        log.info("Replay: client connected from {}", ctx.channel().remoteAddress())
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        log.info("Replay: received type={} requestId={}", msg.clientToMatchServiceMessageType, msg.requestId)

        when (msg.clientToMatchServiceMessageType) {
            ClientToMatchServiceMessageType.AuthenticateRequest_f487 -> handleAuth(ctx, msg)
            ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487 -> handleConnect(ctx, msg)
            ClientToMatchServiceMessageType.ClientToGremessage -> handleGre(ctx, msg)
            else -> log.info("Replay: unhandled type {}", msg.clientToMatchServiceMessageType)
        }
    }

    private fun handleAuth(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        val authReq = AuthenticateRequest.parseFrom(msg.payload)
        val isFamiliar = authReq.clientId.endsWith("_Familiar")
        seatId = if (isFamiliar) 2 else 1
        clientId = authReq.clientId
        log.info("Replay: auth clientId={} → seat={}", clientId, seatId)

        // Build patched auth response with the REAL clientId from this session
        val captured = if (isFamiliar) {
            authResponses.firstOrNull { it.parsed?.authenticateResponse?.clientId?.endsWith("_Familiar") == true }
        } else {
            authResponses.firstOrNull { it.parsed?.authenticateResponse?.clientId?.endsWith("_Familiar") != true }
        }

        if (captured?.parsed != null) {
            val patched = captured.parsed.toBuilder()
                .setRequestId(msg.requestId)
                .setAuthenticateResponse(
                    captured.parsed.authenticateResponse.toBuilder()
                        .setClientId(clientId)
                        .setScreenName(authReq.playerName.ifEmpty { "ForgePlayer" }),
                )
                .build()
            sendProto(ctx, patched, "auth(patched)")
        } else {
            log.warn("Replay: no auth response for seat={}", seatId)
        }
    }

    private fun handleConnect(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        val connectReq = ClientToMatchDoorConnectRequest.parseFrom(msg.payload)
        if (connectReq.matchId.isNotEmpty()) {
            matchId = connectReq.matchId
        }
        // Detect seat from embedded GRE
        if (!connectReq.clientToGreMessageBytes.isEmpty) {
            val greMsg = ClientToGREMessage.parseFrom(connectReq.clientToGreMessageBytes)
            if (greMsg.systemSeatId > 0) seatId = greMsg.systemSeatId
        }
        log.info("Replay: ConnectReq matchId={} seat={}", matchId, seatId)

        // Send patched MatchGameRoomStateChangedEvent
        val roomState = matchRoomStates.removeFirstOrNull()
        if (roomState?.parsed != null) {
            sendProto(ctx, patchMatchId(roomState.parsed), "roomState(patched)")
        }

        // Store context — client is waiting for the first GRE frame via next()
        pendingCtx = ctx
    }

    private fun handleGre(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        val greMsg = ClientToGREMessage.parseFrom(msg.payload)
        log.info("Replay: GRE from client type={} seat={}", greMsg.type, greMsg.systemSeatId)
        // Store context for next() to use — client is waiting for the next server frame
        pendingCtx = ctx
    }

    // -- ReplayController implementation --

    override val currentFrame: Int get() = grePosition
    override val totalFrames: Int get() = greFrameIndex.size
    override val frameIndex: List<ReplayController.FrameInfo> get() = greFrameIndex

    override fun next(): Boolean {
        val ctx = pendingCtx ?: return false
        val cp = popGreForSeat() ?: return false
        sendPatchedGre(ctx, cp)
        grePosition++
        return true
    }

    override fun status() = ReplayController.ReplayStatus(
        currentFrame = grePosition,
        totalFrames = greFrameIndex.size,
        currentFrameInfo = greFrameIndex.getOrNull(grePosition),
        atEnd = grePosition >= greFrameIndex.size,
    )

    /**
     * Patch matchId and clientId in a MatchGameRoomStateChangedEvent.
     */
    private fun patchMatchId(original: MatchServiceToClientMessage): MatchServiceToClientMessage {
        val event = original.matchGameRoomStateChangedEvent
        val roomInfo = event.gameRoomInfo
        val config = roomInfo.gameRoomConfig

        val playerId = clientId.removeSuffix("_Familiar")
        val familiarId = "${playerId}_Familiar"

        // Patch reservedPlayers with current session's clientIds
        val patchedPlayers = config.reservedPlayersList.map { p ->
            val builder = p.toBuilder()
            if (p.systemSeatId == 1) {
                builder.setUserId(playerId)
            } else if (p.systemSeatId == 2) {
                builder.setUserId(familiarId)
            }
            builder.build()
        }

        // Patch players list too
        val patchedTopPlayers = roomInfo.playersList.map { p ->
            val builder = p.toBuilder()
            if (p.systemSeatId == 1) {
                builder.setUserId(playerId)
            } else if (p.systemSeatId == 2) {
                builder.setUserId(familiarId)
            }
            builder.build()
        }

        val patchedConfig = config.toBuilder()
            .setMatchId(matchId)
            .clearReservedPlayers()
            .addAllReservedPlayers(patchedPlayers)

        val patchedRoomInfo = roomInfo.toBuilder()
            .setGameRoomConfig(patchedConfig)
            .clearPlayers()
            .addAllPlayers(patchedTopPlayers)

        val patchedEvent = event.toBuilder().setGameRoomInfo(patchedRoomInfo).build()

        return original.toBuilder()
            .setMatchGameRoomStateChangedEvent(patchedEvent)
            .build()
    }

    /**
     * Pop the next GRE bundle for this seat.
     *
     * A bundle "belongs" to a seat if any message in it is exclusively
     * addressed to that seat (e.g. ConnectResp seats=[1], GameState seats=[1]).
     * DieRoll with seats=[1,2] alone does NOT claim it for either seat.
     * Fallback: if nothing has an exclusive match, take the first with any match.
     */
    private fun popGreForSeat(): CapturedPayload? {
        // Pass 1: find bundle with at least one message exclusively for this seat
        val iter1 = greEvents.iterator()
        while (iter1.hasNext()) {
            val cp = iter1.next()
            val event = cp.parsed?.greToClientEvent ?: continue
            val hasExclusive = event.greToClientMessagesList.any { msg ->
                val seats = msg.systemSeatIdsList
                seats.size == 1 && seats[0] == seatId
            }
            if (hasExclusive) {
                iter1.remove()
                return cp
            }
        }
        // Pass 2: fallback to any bundle targeting this seat
        val iter2 = greEvents.iterator()
        while (iter2.hasNext()) {
            val cp = iter2.next()
            val event = cp.parsed?.greToClientEvent ?: continue
            val seats = event.greToClientMessagesList.flatMap { it.systemSeatIdsList }.toSet()
            if (seats.contains(seatId) || seats.isEmpty()) {
                iter2.remove()
                return cp
            }
        }
        return null
    }

    /**
     * Patch matchId inside GRE GameStateMessage.gameInfo, then send as proto.
     */
    private fun sendPatchedGre(ctx: ChannelHandlerContext, cp: CapturedPayload) {
        val original = cp.parsed
        if (original == null || matchId.isEmpty()) {
            // Can't patch — send raw
            sendRawPayload(ctx, cp)
            return
        }
        val patched = patchGreMatchId(original)
        sendProto(ctx, patched, "gre(patched:${cp.fileName})")
    }

    /**
     * Patch matchId in all GameStateMessage.gameInfo within a GreToClientEvent.
     */
    private fun patchGreMatchId(original: MatchServiceToClientMessage): MatchServiceToClientMessage {
        if (!original.hasGreToClientEvent()) return original

        val event = original.greToClientEvent
        val patchedMsgs = event.greToClientMessagesList.map { greMsg ->
            if (greMsg.hasGameStateMessage() && greMsg.gameStateMessage.hasGameInfo()) {
                val gs = greMsg.gameStateMessage
                val patchedInfo = gs.gameInfo.toBuilder().setMatchID(matchId).build()
                val patchedGs = gs.toBuilder().setGameInfo(patchedInfo).build()
                greMsg.toBuilder().setGameStateMessage(patchedGs).build()
            } else {
                greMsg
            }
        }

        val patchedEvent = event.toBuilder()
            .clearGreToClientMessages()
            .addAllGreToClientMessages(patchedMsgs)
            .build()

        return original.toBuilder()
            .setGreToClientEvent(patchedEvent)
            .build()
    }

    private fun sendProto(ctx: ChannelHandlerContext, msg: MatchServiceToClientMessage, label: String) {
        val bytes = msg.toByteArray()
        log.info("Replay: sending {} ({} bytes)", label, bytes.size)
        ctx.writeAndFlush(Unpooled.wrappedBuffer(bytes))
    }

    private fun sendRawPayload(ctx: ChannelHandlerContext, cp: CapturedPayload) {
        log.info("Replay: sending {} ({} bytes)", cp.fileName, cp.rawBytes.size)
        ctx.writeAndFlush(Unpooled.wrappedBuffer(cp.rawBytes))
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error("Replay: error: {}", cause.message, cause)
        ctx.close()
    }
}

private data class CapturedPayload(
    val fileName: String,
    val rawBytes: ByteArray,
    val parsed: MatchServiceToClientMessage?,
)
