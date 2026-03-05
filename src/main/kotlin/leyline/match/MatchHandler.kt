package leyline.match

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.config.MatchConfig
import leyline.debug.DebugCollector
import leyline.debug.GameStateCollector
import leyline.debug.SessionRecorder
import leyline.debug.Tap
import leyline.game.CardRepository
import leyline.game.GameBridge
import leyline.game.GsmBuilder
import leyline.infra.NettyMessageSink
import leyline.protocol.HandshakeMessages
import leyline.protocol.ProtoDump
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import java.io.File

/**
 * Match Door handler (port 30003) — thin Netty adapter.
 *
 * Pre-mulligan messages (auth, connect, room state, deal hand, mulligan) are handled
 * here using template-based senders. Post-mulligan game orchestration is delegated
 * to [MatchSession].
 */
class MatchHandler(
    private val registry: MatchRegistry = defaultRegistry,
    private val matchConfig: MatchConfig = MatchConfig(),
    /** CLI --puzzle override: forces puzzle mode for all connections. */
    private val puzzleFile: File? = null,
    /** Returns the deckId selected in FD's 612 handler, if any. */
    private val selectedDeckOverride: (() -> String?)? = null,
    /** Look up a deck's cards JSON by deckId. Injected from LeylineServer. */
    private val deckLookup: ((String) -> String?)? = null,
    /** Look up a deck's cards JSON by name. Used for AI deck from config. */
    private val deckLookupByName: ((String) -> String?)? = null,
    /** Card data repository — used for grpId→name in deck conversion. */
    private val cards: CardRepository? = null,
    /** Protocol message collector for debug panel. */
    private val debugCollector: DebugCollector? = null,
    /** Structured game state collector for debug panel. */
    private val gameStateCollector: GameStateCollector? = null,
) : SimpleChannelInboundHandler<ClientToMatchServiceMessage>() {
    private val log = LoggerFactory.getLogger(MatchHandler::class.java)

    private var matchId = "forge-match-1"
    private var clientId = "forge-player-1"
    private var seatId = 1

    /** Netty context — saved for template senders and cross-connection calls. */
    private var nettyCtx: ChannelHandlerContext? = null

    /** Game session — created on connect, holds all post-mulligan state. */
    internal var session: MatchSession? = null

    /** Mulligan flow delegate — owns mulligan state and DealHand/MulliganReq senders. */
    internal val mulliganHandler = MulliganHandler(matchConfig, registry)

    /** Puzzle mode delegate — detection, loading, initial bundle. */
    private val puzzleHandler = PuzzleHandler(puzzleFile, matchConfig, cards, registry)

    companion object {
        val defaultRegistry = MatchRegistry()
        private val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    init {
        // Wire debug collector's bridge/session providers once per handler instance.
        debugCollector?.bridgeProvider = { registry.activeBridges() }
        debugCollector?.sessionProvider = { registry.activeSession() }
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        log.info("Match Door: client connected from {}", ctx.channel().remoteAddress())
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        Tap.inbound(msg.clientToMatchServiceMessageType, msg.requestId)
        debugCollector?.recordInbound(msg)

        when (msg.clientToMatchServiceMessageType) {
            ClientToMatchServiceMessageType.AuthenticateRequest_f487 -> handleMatchAuth(ctx, msg)
            ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487 -> handleMatchDoorConnect(ctx, msg)
            ClientToMatchServiceMessageType.ClientToGremessage -> handleGREMessage(ctx, msg)
            ClientToMatchServiceMessageType.ClientToGreuimessage -> handleGREMessage(ctx, msg)
            else -> log.warn("Match Door: unhandled type: {}", msg.clientToMatchServiceMessageType)
        }
    }

    private fun handleMatchAuth(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        val authReq = AuthenticateRequest.parseFrom(msg.payload)
        clientId = authReq.clientId.ifEmpty { "forge-player-$seatId" }
        val playerName = authReq.playerName.ifEmpty { "ForgePlayer" }
        log.info("Match Door: auth clientId={} playerName={}", clientId, playerName)

        val resp = MatchServiceToClientMessage.newBuilder()
            .setRequestId(msg.requestId)
            .setAuthenticateResponse(
                AuthenticateResponse.newBuilder()
                    .setClientId(clientId)
                    .setSessionId("forge-session-1")
                    .setScreenName(playerName),
            )
            .build()
        ProtoDump.dump(resp, "AuthResp")
        ctx.writeAndFlush(resp)
    }

    private fun handleMatchDoorConnect(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        val connectReq = ClientToMatchDoorConnectRequest.parseFrom(msg.payload)
        if (connectReq.matchId.isNotEmpty()) matchId = connectReq.matchId
        log.info("Match Door: connect matchId={}", matchId)

        if (!connectReq.clientToGreMessageBytes.isEmpty) {
            val greMsg = ClientToGREMessage.parseFrom(connectReq.clientToGreMessageBytes)
            if (greMsg.systemSeatId > 0) seatId = greMsg.systemSeatId
            log.info("Match Door: detected seatId={}", seatId)
            nettyCtx = ctx

            // Create session + sink + recorder
            // Seat 2 (Familiar) sink skips ProtoDump to avoid duplicate .bin files
            val sink = NettyMessageSink(ctx, dumpEnabled = seatId == 1)
            val rec = SessionRecorder(mode = "engine")
            SessionRecorder.register(rec)
            val s = MatchSession(
                seatId,
                matchId,
                sink,
                registry,
                recorder = rec,
                debugCollector = debugCollector,
                gameStateCollector = gameStateCollector,
            )
            s.playerId = clientId.removeSuffix("_Familiar")
            session = s
            registry.registerSession(matchId, seatId, s)
            registry.registerHandler(matchId, seatId, this)

            // Wire mulligan delegate
            mulliganHandler.sessionProvider = { session }
            mulliganHandler.ctxProvider = { nettyCtx }
            mulliganHandler.matchId = matchId
            mulliganHandler.seatId = seatId

            processGREMessage(ctx, greMsg)
        }
    }

    private fun handleGREMessage(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        processGREMessage(ctx, ClientToGREMessage.parseFrom(msg.payload))
    }

    private fun processGREMessage(ctx: ChannelHandlerContext, greMsg: ClientToGREMessage) {
        Tap.inboundGRE(greMsg.type, greMsg.systemSeatId, greMsg.gameStateId)
        val s = session

        when (greMsg.type) {
            ClientMessageType.ConnectReq_097b -> {
                // Evict stale bridges from previous matches and reset debug collectors
                val evicted = registry.evictStale(matchId)
                if (evicted.isNotEmpty()) {
                    evicted.forEach { it.shutdown() }
                    debugCollector?.clear()
                    gameStateCollector?.clear()
                    log.info("Match Door: evicted {} stale bridge(s)", evicted.size)
                }

                if (puzzleHandler.isPuzzleMatch(matchId)) {
                    sendRoomState(ctx)
                    puzzleHandler.onPuzzleConnect(ctx, s!!, matchId, seatId)
                } else {
                    // Constructed mode: normal flow
                    val bridge = registry.getOrCreateBridge(matchId) {
                        GameBridge(matchConfig = matchConfig, messageCounter = s!!.counter, cards = cards ?: leyline.game.InMemoryCardRepository()).also {
                            it.start(
                                seed = matchConfig.game.seed,
                                deckList1 = resolveSeat1Deck(),
                                deckList2 = resolveSeat2Deck(),
                            )
                        }
                    }
                    s?.connectBridge(bridge)
                    mulliganHandler.seat1Hand = bridge.getHandGrpIds(1)
                    mulliganHandler.seat2Hand = bridge.getHandGrpIds(2)
                    log.info("Match Door: seat {} connected, hands seat1={} seat2={}", seatId, mulliganHandler.seat1Hand, mulliganHandler.seat2Hand)
                    sendRoomState(ctx)
                    sendInitialBundle(ctx)
                }
            }

            ClientMessageType.ChooseStartingPlayerResp_097b ->
                mulliganHandler.onChooseStartingPlayer(this)

            ClientMessageType.MulliganResp_097b ->
                mulliganHandler.onMulliganResp(greMsg)

            // Future: proper London tuck UI via GroupReq/GroupResp.
            // Currently unreachable — auto-tuck in submitMull() handles tuck during mull.
            // Will be wired when we send GroupReq to client instead of auto-tucking.
            ClientMessageType.GroupResp_097b ->
                mulliganHandler.onGroupResp(greMsg)

            ClientMessageType.SetSettingsReq_097b -> {
                s?.onSettings(greMsg)
            }

            ClientMessageType.ConcedeReq_097b -> {
                log.info("Match Door GRE: concede")
                s?.onConcede()
            }

            ClientMessageType.PerformActionResp_097b -> {
                if (seatId == 1) {
                    s?.onPerformAction(greMsg)
                } else {
                    log.debug("Match Door: ignoring PerformActionResp from Familiar (seat {})", seatId)
                }
            }

            ClientMessageType.DeclareAttackersResp_097b,
            ClientMessageType.SubmitAttackersReq,
            -> {
                if (seatId == 1) {
                    s?.onDeclareAttackers(greMsg)
                } else {
                    log.debug("Match Door: ignoring DeclareAttackersResp from Familiar (seat {})", seatId)
                }
            }

            ClientMessageType.DeclareBlockersResp_097b,
            ClientMessageType.SubmitBlockersReq,
            -> {
                if (seatId == 1) {
                    s?.onDeclareBlockers(greMsg)
                } else {
                    log.debug("Match Door: ignoring DeclareBlockersResp from Familiar (seat {})", seatId)
                }
            }

            ClientMessageType.SelectTargetsResp_097b -> {
                if (seatId == 1) {
                    s?.onSelectTargets(greMsg)
                }
            }

            ClientMessageType.CancelActionReq_097b -> {
                if (seatId == 1) {
                    s?.onCancelAction(greMsg)
                }
            }

            ClientMessageType.SelectNresp -> {
                if (seatId == 1) {
                    s?.onSelectN(greMsg)
                }
            }

            ClientMessageType.CheckpointReq -> {
                // Client acknowledges IntermissionReq — MatchCompleted room state
                // was already sent in sendGameOver(). Nothing to do here.
                log.info("Match Door GRE: CheckpointReq (post-game acknowledgement)")
            }

            // Cosmetic UI relay (emotes, card hover, pet animations) — no game state impact.
            // In single-player-vs-AI context there's nobody to relay to; silently ignore.
            ClientMessageType.Uimessage_a39e -> { }

            else -> log.warn("Match Door GRE: unhandled type: {}", greMsg.type)
        }
    }

    // --- Template-based senders (pre-mulligan, use ctx directly) ---

    private fun sendRoomState(ctx: ChannelHandlerContext) {
        val playerId = clientId.removeSuffix("_Familiar")
        val msg = HandshakeMessages.roomState(matchId, playerId)
        Tap.outboundTemplate("RoomState matchId=$matchId")
        ProtoDump.dump(msg, "RoomState")
        ctx.writeAndFlush(msg)
    }

    private fun sendInitialBundle(ctx: ChannelHandlerContext) {
        val s = session ?: return
        val bridge = s.gameBridge ?: return
        val gsId = s.counter.nextGsId()
        val deckGrpIds = bridge.getDeckGrpIds(seatId)
        val deck = GsmBuilder.buildDeckMessage(deckGrpIds)
        val (msg, nextMsgId) = HandshakeMessages.initialBundle(
            seatId,
            matchId,
            s.counter.currentMsgId(),
            gsId,
            deck,
            bridge,
            dieRollWinner = matchConfig.game.dieRollWinner,
        )
        s.counter.setMsgId(nextMsgId)
        Tap.outboundTemplate("InitialBundle seat=$seatId")
        ProtoDump.dump(msg, "InitialBundle-seat$seatId")
        ctx.writeAndFlush(msg)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        log.info("Match Door: client disconnected")
        // Close recorder on disconnect (triggers analysis if game didn't end cleanly)
        session?.recorder?.run {
            close()
            SessionRecorder.unregister(this)
        }
        super.channelInactive(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error("Match Door error: {}", cause.message, cause)
        session?.recorder?.run {
            close()
            SessionRecorder.unregister(this)
        }
        session?.gameBridge?.shutdown()
        ctx.close()
    }

    /**
     * Resolve seat 1 deck: FD captured a deckId from 612 → look it up in player.db
     * and convert grpIds → card names for Forge engine.
     */
    private fun resolveSeat1Deck(): String {
        val deckId = selectedDeckOverride?.invoke()
        if (deckId != null && deckLookup != null) {
            val cardsJson = deckLookup.invoke(deckId)
            if (cardsJson != null) {
                log.info("Match Door: seat 1 deck from DB deckId={}", deckId)
                return convertArenaCardsToDeckText(cardsJson)
            }
            log.warn("Match Door: deckId {} not in DB", deckId)
        }
        error("No deck selected for seat 1 — select a deck in the Arena client before queuing")
    }

    /**
     * Resolve seat 2 (AI) deck: look up by name from player.db.
     * Falls back to seat 1's deck (mirror match) if AI deck not found.
     */
    private fun resolveSeat2Deck(): String {
        // Try AI deck from config (name-based lookup)
        val aiDeckName = matchConfig.game.aiDeck
        if (aiDeckName != null && deckLookupByName != null) {
            val cardsJson = deckLookupByName.invoke(aiDeckName)
            if (cardsJson != null) {
                log.info("Match Door: seat 2 deck from DB name={}", aiDeckName)
                return convertArenaCardsToDeckText(cardsJson)
            }
            log.warn("Match Door: AI deck '{}' not in DB, mirroring seat 1", aiDeckName)
        }
        // Default: mirror seat 1
        return resolveSeat1Deck()
    }

    /** Parse Arena cards JSON → Forge deck text (qty + name per line). */
    private fun convertArenaCardsToDeckText(cardsJson: String): String {
        val obj = lenientJson.parseToJsonElement(cardsJson).jsonObject
        val sb = StringBuilder()
        for (section in listOf("MainDeck", "Sideboard")) {
            val arr = obj[section]?.jsonArray ?: continue
            if (section == "Sideboard" && arr.isNotEmpty()) sb.appendLine("Sideboard")
            for (entry in arr) {
                val cardObj = entry.jsonObject
                val grpId = cardObj["cardId"]?.jsonPrimitive?.int ?: continue
                val qty = cardObj["quantity"]?.jsonPrimitive?.int ?: continue
                val name = cards?.findNameByGrpId(grpId)
                if (name != null) {
                    sb.appendLine("$qty $name")
                } else {
                    log.warn("Match Door: unknown grpId {} in deck", grpId)
                }
            }
        }
        return sb.toString()
    }
}
