package leyline.match

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.bridge.CardEntry
import leyline.bridge.DeckConverter
import leyline.config.MatchConfig
import leyline.frontdoor.service.MatchCoordinator
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
 * Netty adapter for the Match Door (port 30003) — two-phase message flow.
 *
 * **Pre-mulligan:** auth, connect, room state, deal hand, and mulligan use templated
 * proto senders (fixed message shapes). **Post-mulligan:** all game actions delegate
 * to [MatchSession], which drives the engine via bridge futures. The phase boundary
 * is [MatchSession.onMulliganKeep] — after that call, this handler only dispatches.
 * Mulligan and puzzle sub-flows are extracted into [MulliganHandler] / [PuzzleHandler]
 * to keep this class a thin Netty routing layer.
 */
class MatchHandler(
    private val registry: MatchRegistry = defaultRegistry,
    private val matchConfig: MatchConfig = MatchConfig(),
    /** CLI --puzzle override: forces puzzle mode for all connections. */
    private val puzzleFile: File? = null,
    /** Cross-BC coordinator — deck/event selection, deck resolution, match results. */
    private val coordinator: MatchCoordinator? = null,
    /** Card data repository — used for grpId→name in deck conversion. */
    private val cards: CardRepository? = null,
    /** Debug diagnostics sink — protocol messages + game state collector. Null in tests. */
    private val debugSink: MatchDebugSink? = null,
    /** Factory for per-session recorders. Null = no recording. */
    private val recorderFactory: (() -> MatchRecorder)? = null,
) : SimpleChannelInboundHandler<ClientToMatchServiceMessage>() {
    private val log = LoggerFactory.getLogger(MatchHandler::class.java)

    private var matchId = "forge-match-1"
    private var clientId = "forge-player-1"
    private var seatId = 1

    /** True when this connection is the Familiar (spectator), detected by `_Familiar` client ID suffix. */
    private var isFamiliar = false

    /** Netty context — saved for template senders and cross-connection calls. */
    private var nettyCtx: ChannelHandlerContext? = null

    /** Game session — created on connect, holds all post-mulligan state. */
    internal var session: MatchSession? = null

    /** Mulligan flow delegate — owns mulligan state and DealHand/MulliganReq senders. */
    internal val mulliganHandler = MulliganHandler(
        matchConfig,
        registry,
        sessionProvider = { session },
        ctxProvider = { nettyCtx },
        matchIdProvider = { matchId },
        seatIdProvider = { seatId },
    )

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
        debugSink?.bridgeProvider = { registry.activeBridges() }
        debugSink?.sessionProvider = { registry.activeSession() }
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        log.info("Match Door: client connected from {}", ctx.channel().remoteAddress())
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        Tap.inbound(msg.clientToMatchServiceMessageType, msg.requestId)
        debugSink?.recordInbound(msg)

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
        isFamiliar = clientId.endsWith("_Familiar")
        val playerName = authReq.playerName.ifEmpty { "ForgePlayer" }
        log.info("Match Door: auth clientId={} playerName={} familiar={}", clientId, playerName, isFamiliar)

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
            val sink = NettyMessageSink(ctx, dumpEnabled = !isFamiliar)
            val rec = recorderFactory?.invoke()
            val s = MatchSession(
                seatId,
                matchId,
                sink,
                registry,
                recorder = rec,
                debugSink = debugSink,
                coordinator = coordinator,
            )
            s.playerId = clientId.removeSuffix("_Familiar")
            session = s
            registry.registerSession(matchId, seatId, s)
            registry.registerHandler(matchId, seatId, this)

            processGREMessage(ctx, greMsg)
        }
    }

    private fun handleGREMessage(ctx: ChannelHandlerContext, msg: ClientToMatchServiceMessage) {
        val greMsg = ClientToGREMessage.parseFrom(msg.payload)
        processGREMessage(ctx, greMsg)
    }

    private fun processGREMessage(ctx: ChannelHandlerContext, greMsg: ClientToGREMessage) {
        Tap.inboundGRE(greMsg.type, greMsg.systemSeatId, greMsg.gameStateId)
        val s = session

        when (greMsg.type) {
            ClientMessageType.ConnectReq_097b -> {
                val eventName = coordinator?.selectedEventName
                if (eventName != null) log.info("Match Door: event={}", eventName)

                // Evict stale bridges from previous matches and reset debug collectors
                val evicted = registry.evictStale(matchId)
                if (evicted.isNotEmpty()) {
                    debugSink?.clear()
                    debugSink?.clearState()
                    log.info("Match Door: evicted {} stale match(es)", evicted.size)
                }

                if (puzzleHandler.isPuzzleMatch(matchId)) {
                    sendRoomState(ctx)
                    puzzleHandler.onPuzzleConnect(ctx, s!!, matchId, seatId)
                } else {
                    // Constructed mode: normal flow
                    val match = registry.getOrCreateMatch(matchId) {
                        val bridge = GameBridge(matchConfig = matchConfig, messageCounter = s!!.counter, cards = cards ?: leyline.game.InMemoryCardRepository())
                        Match(matchId, bridge).also {
                            it.start(
                                seed = matchConfig.game.seed,
                                deckList1 = resolveSeat1Deck(),
                                deckList2 = resolveSeat2Deck(),
                            )
                        }
                    }
                    val bridge = match.bridge
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

            // GroupResp routes to mulligan handler (London tuck) or session (surveil/scry).
            // During mulligan phase, route to mulligan handler; otherwise to session.
            ClientMessageType.GroupResp_097b -> {
                if (s?.gameBridge?.promptBridge?.getPendingPrompt() != null) {
                    s.onGroupResp(greMsg)
                } else {
                    mulliganHandler.onGroupResp(greMsg)
                }
            }

            ClientMessageType.SetSettingsReq_097b -> {
                s?.onSettings(greMsg)
            }

            ClientMessageType.ConcedeReq_097b -> {
                log.info("Match Door GRE: concede")
                s?.onConcede()
            }

            ClientMessageType.PerformActionResp_097b -> {
                if (!isFamiliar) {
                    s?.onPerformAction(greMsg)
                } else {
                    log.debug("Match Door: ignoring PerformActionResp from Familiar (seat {})", seatId)
                }
            }

            ClientMessageType.DeclareAttackersResp_097b -> {
                if (!isFamiliar) {
                    s?.onDeclareAttackers(greMsg)
                } else {
                    log.debug("Match Door: ignoring DeclareAttackersResp from Familiar (seat {})", seatId)
                }
            }

            // SubmitAttackersReq is a type-only signal ("Done" button, no payload).
            // The client may send it on either channel (seat 1 or 2) — race condition
            // in the Arena client. Combat state (lastDeclaredAttackerIds) lives on
            // the player's CombatHandler, so always route to the player's session.
            ClientMessageType.SubmitAttackersReq -> {
                val target = if (!isFamiliar) s else registry.activeSession()
                target?.onDeclareAttackers(greMsg)
            }

            ClientMessageType.DeclareBlockersResp_097b -> {
                if (!isFamiliar) {
                    s?.onDeclareBlockers(greMsg)
                } else {
                    log.debug("Match Door: ignoring DeclareBlockersResp from Familiar (seat {})", seatId)
                }
            }

            // Same pattern as SubmitAttackersReq — route to player's session.
            ClientMessageType.SubmitBlockersReq -> {
                val target = if (!isFamiliar) s else registry.activeSession()
                target?.onDeclareBlockers(greMsg)
            }

            ClientMessageType.SelectTargetsResp_097b -> {
                if (!isFamiliar) {
                    s?.onSelectTargets(greMsg)
                }
            }

            ClientMessageType.CancelActionReq_097b -> {
                if (!isFamiliar) {
                    s?.onCancelAction(greMsg)
                }
            }

            ClientMessageType.SelectNresp -> {
                if (!isFamiliar) {
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
        session?.recorder?.shutdown()
        super.channelInactive(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error("Match Door error: {}", cause.message, cause)
        session?.recorder?.shutdown()
        // Route through Match.close() for proper lifecycle transition + callback
        registry.getMatch(matchId)?.close() ?: session?.gameBridge?.shutdown()
        ctx.close()
    }

    /**
     * Resolve seat 1 deck: FD captured a deckId from 612 → look it up in player.db
     * and convert grpIds → card names for Forge engine.
     */
    private fun resolveSeat1Deck(): String {
        val deckId = coordinator?.selectedDeckId
        if (deckId != null) {
            val cardsJson = coordinator?.resolveDeckJson(deckId)
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
        if (aiDeckName != null && coordinator != null) {
            val cardsJson = coordinator.resolveDeckJsonByName(aiDeckName)
            if (cardsJson != null) {
                log.info("Match Door: seat 2 deck from DB name={}", aiDeckName)
                return convertArenaCardsToDeckText(cardsJson)
            }
            log.warn("Match Door: AI deck '{}' not in DB, mirroring seat 1", aiDeckName)
        }
        // Default: mirror seat 1
        return resolveSeat1Deck()
    }

    /** Parse Arena cards JSON → Forge deck text (qty + name per line). Delegates to [DeckConverter]. */
    private fun convertArenaCardsToDeckText(cardsJson: String): String {
        val obj = lenientJson.parseToJsonElement(cardsJson).jsonObject
        val mainDeck = parseDeckSection(obj, "MainDeck")
        val sideboard = parseDeckSection(obj, "Sideboard")
        return DeckConverter.toDeckText(mainDeck, sideboard, cards!!::findNameByGrpId)
    }

    private fun parseDeckSection(
        obj: kotlinx.serialization.json.JsonObject,
        section: String,
    ): List<CardEntry> {
        val arr = obj[section]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { entry ->
            val cardObj = entry.jsonObject
            val grpId = cardObj["cardId"]?.jsonPrimitive?.int ?: return@mapNotNull null
            val qty = cardObj["quantity"]?.jsonPrimitive?.int ?: return@mapNotNull null
            CardEntry(grpId, qty)
        }
    }
}
