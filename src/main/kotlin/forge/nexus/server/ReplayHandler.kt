package forge.nexus.server

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import java.io.File
import kotlin.collections.get

/**
 * Match Door handler that replays recorded binary payloads.
 *
 * Patches matchId and clientId in replayed messages to match the current
 * session (the Front Door assigns new IDs each time).
 *
 * Usage: `--replay <payloads-dir>` (default: NexusPaths.CAPTURE_PAYLOADS)
 */
class ReplayHandler(
    private val payloadDir: File,
) : SimpleChannelInboundHandler<ClientToMatchServiceMessage>() {

    private val log = LoggerFactory.getLogger(ReplayHandler::class.java)

    private var seatId = 0
    private var clientId = ""
    private var matchId = ""

    private val authResponses: MutableList<CapturedPayload>
    private val matchRoomStates: MutableList<CapturedPayload>
    private val greEvents: MutableList<CapturedPayload>
    private val uncategorized: MutableList<CapturedPayload>

    init {
        val allFiles = payloadDir.listFiles()
            ?.filter { it.name.startsWith("S-C_MATCH") }
            ?.sortedBy { it.name }
            ?: emptyList()

        val auths = mutableListOf<CapturedPayload>()
        val rooms = mutableListOf<CapturedPayload>()
        val gres = mutableListOf<CapturedPayload>()
        val other = mutableListOf<CapturedPayload>()

        for (file in allFiles) {
            val bytes = file.readBytes()
            val parsed = try {
                MatchServiceToClientMessage.parseFrom(bytes)
            } catch (_: Exception) {
                null
            }
            val cp = CapturedPayload(file.name, bytes, parsed)

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

        // Send next GRE bundle for this seat (patched)
        val greBundle = popGreForSeat()
        if (greBundle != null) sendPatchedGre(ctx, greBundle)
    }

    private fun handleGre(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        val greMsg = ClientToGREMessage.parseFrom(msg.payload)
        log.info("Replay: GRE type={} seat={}", greMsg.type, greMsg.systemSeatId)

        val next = popGreForSeat()
        if (next != null) {
            sendPatchedGre(ctx, next)
        } else {
            log.info("Replay: no more GRE responses for seat={}", seatId)
        }
    }

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
