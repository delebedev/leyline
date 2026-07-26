package leyline.match

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.bridge.bootstrap.CardEntry
import leyline.bridge.bootstrap.DeckConverter
import leyline.bridge.types.SeatId
import leyline.config.MatchConfig
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.json.productionJson
import leyline.domain.service.MatchCoordinator
import leyline.game.bundle.GsmBuilder
import leyline.game.bundle.MessageCounter
import leyline.game.data.CardRepository
import leyline.game.state.GameBridge
import leyline.infra.MatchOutput
import leyline.infra.MatchOutputMessageSink
import leyline.protocol.HandshakeMessages
import leyline.protocol.ProtoDump
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Transport-neutral GRE match connection — routes parsed match-service messages into the engine.
 *
 * **Pre-mulligan:** auth, connect, room state, deal hand, and mulligan use templated
 * proto senders (fixed message shapes). **Post-mulligan:** all game actions delegate
 * to [MatchSession], which drives the engine via bridge futures. The phase boundary
 * is [MatchSession.onMulliganKeep] — after that call, this handler only dispatches.
 * Mulligan and puzzle sub-flows are extracted into [MulliganHandler] / [PuzzleHandler]
 * to keep this class a thin message-routing layer.
 */
class MatchConnection(
    private val registry: MatchRegistry,
    private val output: MatchOutput,
    private val matchConfig: MatchConfig = MatchConfig(),
    /** Cross-BC coordinator — deck/event selection, deck resolution, match results. */
    private val coordinator: MatchCoordinator? = null,
    /** Card data repository — used for grpId→name in deck conversion. */
    private val cardRepository: CardRepository,
    /** Debug server wiring — session/bridge providers. Null in tests. */
    private val debugSink: MatchDebugSink? = null,
    /** Factory for per-session action recorders. */
    private val recorderFactory: (() -> MatchRecorder)? = null,
    /** Runtime puzzle file path supplier — non-null activates puzzle mode. */
    private val puzzlePath: () -> String? = { null },
    /** MatchId-keyed runtime config for web/native clients. */
    private val runtimeMatchConfigs: RuntimeMatchConfigRegistry? = null,
) {
    private val log = LoggerFactory.getLogger(MatchConnection::class.java)

    /**
     * Connection lifecycle. Identity accrues while [MatchHandlerState.Handshaking]
     * (auth → connect request), then freezes into [MatchHandlerState.Connected] once
     * the session exists. A displaced transport becomes [MatchHandlerState.Detached]
     * and cannot act on the replacement generation.
     */
    @Volatile private var state: MatchHandlerState = MatchHandlerState.Handshaking()

    private val handshaking: MatchHandlerState.Handshaking?
        get() = state as? MatchHandlerState.Handshaking

    private val connected: MatchHandlerState.Connected?
        get() = state as? MatchHandlerState.Connected

    private val matchId: String
        get() =
            when (val s = state) {
                is MatchHandlerState.Handshaking -> s.matchId
                is MatchHandlerState.Connected -> s.matchId
                MatchHandlerState.Detached -> error("Detached connection has no match identity")
            }

    private val clientId: String
        get() =
            when (val s = state) {
                is MatchHandlerState.Handshaking -> s.clientId
                is MatchHandlerState.Connected -> s.clientId
                MatchHandlerState.Detached -> error("Detached connection has no client identity")
            }

    private val seatId: Int
        get() =
            when (val s = state) {
                is MatchHandlerState.Handshaking -> s.seatId
                is MatchHandlerState.Connected -> s.seatId
                MatchHandlerState.Detached -> error("Detached connection has no seat identity")
            }

    private val isFamiliar: Boolean
        get() =
            when (val s = state) {
                is MatchHandlerState.Handshaking -> s.isFamiliar
                is MatchHandlerState.Connected -> s.isFamiliar
                MatchHandlerState.Detached -> error("Detached connection has no familiar identity")
            }

    /**
     * Game session — null until connect completes, reassigned on puzzle hot-swap.
     * Reads/writes are backed by the [MatchHandlerState.Connected] state.
     */
    internal var session: SessionOps?
        get() = connected?.session
        set(value) {
            bindSession(value ?: error("session cannot be cleared via assignment"))
        }

    private var spectatorRandomDeckPair: Pair<String, String>? = null

    /** Mulligan flow delegate — owns mulligan state and DealHand/MulliganReq senders. */
    internal val mulliganHandler =
        MulliganHandler(
            matchConfig,
            registry,
            sessionProvider = { session as? GameOps },
            matchIdProvider = { matchId },
            seatIdProvider = { SeatId(seatId) },
        )

    /** Puzzle mode delegate — detection, loading, initial bundle. */
    private val puzzleHandler = PuzzleHandler(::resolvePuzzlePath, cardRepository, registry, matchConfig)

    private val connectFlow =
        MatchConnectFlow(
            registry = registry,
            matchConfig = matchConfig,
            coordinator = coordinator,
            cardRepository = cardRepository,
            puzzleHandler = puzzleHandler,
            output = output,
            createMatchSession = ::createAndRegisterMatchSession,
            onFamiliarConnected = ::onFamiliarConnected,
            createSpectatorSession = ::createAndRegisterSpectatorSession,
            sendRoomState = ::sendRoomState,
            sendInitialBundle = ::sendInitialBundle,
            resolveSeatDecks = { resolveSeatDecks().let { it.seat1 to it.seat2 } },
            resolveGameVariant = ::resolveGameVariant,
            isSpectatorMode = ::isSpectatorMode,
            onLocalPlayerConnected = ::onLocalPlayerConnected,
        )

    companion object {
        private val lenientJson =
            productionJson {
                ignoreUnknownKeys = true
                isLenient = true
            }
    }

    init {
        // Wire local-control bridge/session providers once per handler instance.
        debugSink?.sessionProvider = { registry.activeSession() }
    }

    private fun resolvePuzzlePath(matchId: String): String? {
        val config = runtimeMatchConfigs?.get(matchId)
        if (config != null) return config.puzzle?.takeIf { it.isNotBlank() }
        return puzzlePath()
    }

    private fun resolveRuntimeMatchConfig(): RuntimeMatchConfig? = runtimeMatchConfigs?.get(matchId)

    private fun isSpectatorMode(): Boolean = runtimeMatchConfigs?.get(matchId)?.spectatorMode ?: matchConfig.game.spectatorMode

    fun opened() {
        log.info("Match connection opened")
    }

    fun receive(msg: ClientToMatchServiceMessage) {
        if (state === MatchHandlerState.Detached) {
            log.debug("Match Door: ignoring message on detached connection")
            return
        }
        Tap.inbound(msg.clientToMatchServiceMessageType)

        when (msg.clientToMatchServiceMessageType) {
            ClientToMatchServiceMessageType.AuthenticateRequest_f487 -> handleMatchAuth(msg)
            ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487 -> handleMatchDoorConnect(msg)
            ClientToMatchServiceMessageType.ClientToGremessage -> handleGREMessage(msg)
            ClientToMatchServiceMessageType.ClientToGreuimessage -> handleGREMessage(msg)
            ClientToMatchServiceMessageType.None_a0f0,
            ClientToMatchServiceMessageType.CreateMatchGameRoomRequest_f487,
            ClientToMatchServiceMessageType.EchoRequest_f487,
            ClientToMatchServiceMessageType.UNRECOGNIZED,
            -> log.warn("Match Door: unhandled type: {}", msg.clientToMatchServiceMessageType)
        }
    }

    private fun handleMatchAuth(msg: ClientToMatchServiceMessage) {
        val authReq = AuthenticateRequest.parseFrom(msg.payload)
        val hs = requireHandshaking()
        hs.clientId = authReq.clientId.ifEmpty { "leyline-player-${hs.seatId}" }
        hs.isFamiliar = hs.clientId.endsWith("_Familiar")
        val playerName = authReq.playerName.ifEmpty { "Player" }
        log.info("Match Door: auth clientId={} playerName={} familiar={}", clientId, playerName, isFamiliar)

        val resp =
            MatchServiceToClientMessage
                .newBuilder()
                .setRequestId(msg.requestId)
                .setAuthenticateResponse(
                    AuthenticateResponse
                        .newBuilder()
                        .setClientId(clientId)
                        .setSessionId("forge-session-1")
                        .setScreenName(playerName),
                ).build()
        ProtoDump.dump(resp, "AuthResp")
        output.send(resp)
    }

    private fun handleMatchDoorConnect(msg: ClientToMatchServiceMessage) {
        val connectReq = ClientToMatchDoorConnectRequest.parseFrom(msg.payload)
        val hs = requireHandshaking()
        if (connectReq.matchId.isNotEmpty()) hs.matchId = connectReq.matchId
        log.info("Match Door: connect matchId={}", hs.matchId)

        if (connectReq.clientToGreMessageBytes.isEmpty) return

        val greMsg = ClientToGREMessage.parseFrom(connectReq.clientToGreMessageBytes)
        if (greMsg.systemSeatId > 0) hs.seatId = greMsg.systemSeatId
        log.info("Match Door: detected seatId={}", hs.seatId)
        // Session creation deferred to the ConnectReq branch in processGREMessage:
        // MatchSession requires a non-null bridge at construction, so we wait for
        // getOrCreateMatch() to build the bridge before constructing the session.
        processGREMessage(greMsg)
    }

    /**
     * Return the current handshake, or re-open one on top of an already-[Connected]
     * state.
     *
     * A single [MatchHandler] instance can outlive more than one physical connection
     * attempt — the web relay attaches a fresh browser socket to the same engine
     * instance on every reconnect (page reload, retried websocket, duplicate
     * attach), unlike the native transport where every TCP connection gets its own
     * handler. Without this, a second Auth+Connect handshake on an already-Connected
     * handler used to `error()`, which [exceptionCaught] swallows into a silent
     * match teardown — the client gets no reply and no further game state, ever.
     * Re-opening the handshake (seeded with the frozen identity so an incoming
     * request that omits matchId/seatId still resolves correctly) lets the normal
     * connect flow run again and resend a full resync bundle instead.
     */
    private fun requireHandshaking(): MatchHandlerState.Handshaking {
        handshaking?.let { return it }
        val prior = connected ?: error("Expected handshaking state but connection is already established")
        log.info("Match Door: reconnect on already-established matchId={} seatId={}, resyncing", prior.matchId, prior.seatId)
        (prior.session as? MatchSession)?.close()
        val reopened =
            MatchHandlerState.Handshaking().also {
                it.matchId = prior.matchId
                it.clientId = prior.clientId
                it.seatId = prior.seatId
                it.isFamiliar = prior.isFamiliar
            }
        state = reopened
        return reopened
    }

    /**
     * Freeze the accrued handshake identity and bind the session — Handshaking → Connected.
     * The connect ctx was already stored in [MatchHandlerState.Handshaking.nettyCtx] during
     * the connect request; on re-bind (puzzle hot-swap) identity stays frozen and only the
     * session is replaced.
     */
    private fun bindSession(session: SessionOps) {
        state =
            when (val s = state) {
                is MatchHandlerState.Handshaking ->
                    MatchHandlerState.Connected(s.matchId, s.clientId, s.seatId, s.isFamiliar, session)
                is MatchHandlerState.Connected ->
                    MatchHandlerState.Connected(s.matchId, s.clientId, s.seatId, s.isFamiliar, session)
                MatchHandlerState.Detached -> error("Cannot bind a detached connection")
            }
    }

    /** Create and register a [MatchSession] bound to [bridge]. */
    private fun createAndRegisterMatchSession(bridge: GameBridge): MatchSession {
        val sink = MatchOutputMessageSink(output, dumpEnabled = true)
        val rec = recorderFactory?.invoke()
        val connection =
            ConnectionState(
                seatId = SeatId(seatId),
                matchId = matchId,
                sink = sink,
                registry = registry,
                recorder = rec,
                coordinator = coordinator,
            ).also { it.playerId = clientId.removeSuffix("_Familiar") }
        val s = MatchSession(connection = connection, gameBridge = bridge, paceDelayMs = matchConfig.paceDelayMs)
        s.withSessionAuthority {
            registry.publishSessionAndConnection(matchId, SeatId(seatId), s, this) {
                bindSession(s)
            }
        }
        return s
    }

    /** Create and register a [FamiliarSession] sharing [counter] with the paired match's bridge. */
    private fun createAndRegisterFamiliarSession(counter: MessageCounter): FamiliarSession {
        val sink = MatchOutputMessageSink(output, dumpEnabled = false)
        val s = FamiliarSession(SeatId(seatId), matchId, sink, counter = counter)
        registry.publishSessionAndConnection(matchId, SeatId(seatId), s, this) {
            bindSession(s)
        }
        return s
    }

    private fun onFamiliarConnected(counter: MessageCounter) {
        registry.ownerFor(matchId).reduce {
            createAndRegisterFamiliarSession(counter)
            sendInitialBundleOwned()
        }
    }

    private fun createAndRegisterSpectatorSession(bridge: GameBridge): SpectatorSession {
        val sink = MatchOutputMessageSink(output, dumpEnabled = true)
        val s = SpectatorSession(SeatId(seatId), matchId, sink, bridge, playerId = clientId.removeSuffix("_Familiar"))
        registry.publishSessionAndConnection(matchId, SeatId(seatId), s, this) {
            bindSession(s)
        }
        return s
    }

    private fun handleGREMessage(msg: ClientToMatchServiceMessage) {
        val greMsg = ClientToGREMessage.parseFrom(msg.payload)
        processGREMessage(greMsg)
    }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun processGREMessage(greMsg: ClientToGREMessage) {
        Tap.inboundGRE(greMsg.type, greMsg.systemSeatId, greMsg.gameStateId)

        // Pre-session messages drive the handshake/mulligan flow, which read session
        // state defensively through providers. Everything else is a post-handshake
        // game action that requires a live session — dispatched against Connected.
        when (greMsg.type) {
            ClientMessageType.ConnectReq_097b -> connectFlow.onConnect(ConnectAttempt(matchId, seatId, isFamiliar))

            ClientMessageType.ChooseStartingPlayerResp_097b ->
                withValidSessionResponse(greMsg) { mulliganHandler.onChooseStartingPlayer() }

            ClientMessageType.MulliganResp_097b ->
                withValidSessionResponse(greMsg) { mulliganHandler.onMulliganResp(greMsg) }

            // GroupResp routes to mulligan handler (London tuck) or session (surveil/scry).
            // During mulligan phase, route to mulligan handler; otherwise to session.
            ClientMessageType.GroupResp_097b -> withValidSessionResponse(greMsg) { dispatchGroupResp(greMsg) }

            else -> dispatchToSession(greMsg)
        }
    }

    /**
     * Enter the human session's current game-logic authority.
     *
     * Pre-game responses can arrive on the Familiar connection, whose read-only
     * session has no authority of its own. Both connections therefore resolve the
     * shared human [MatchSession] before driving mulligan or group-response logic.
     */
    private fun withSessionAuthority(action: (MatchSession) -> Unit) {
        val authority = sessionAuthority()
        if (authority == null) {
            log.warn("Match Door GRE: game-logic response before session authority exists")
            return
        }
        authority.withSessionAuthority { action(authority) }
    }

    /**
     * Validate prompt correlation after entering the same authority that will
     * consume the response. The prompt cannot advance between validation and
     * dispatch.
     */
    private fun withValidSessionResponse(
        greMsg: ClientToGREMessage,
        action: () -> Unit,
    ) {
        withSessionAuthority { authority ->
            val responseSession = session ?: authority
            if (!ResponseEnvelopeGuard.rejectMismatch(
                    greMsg,
                    authority.connection.owner.lastPromptMsgId(),
                    authority.counter,
                    responseSession,
                )
            ) {
                action()
            }
        }
    }

    private fun sessionAuthority(): MatchSession? {
        val bridge = registry.getMatch(matchId)?.bridge
        val humanSeat = bridge?.seating?.humanSeat ?: SeatId(1)
        return (session as? MatchSession)
            ?: (registry.getConnection(matchId, humanSeat)?.session as? MatchSession)
    }

    private fun dispatchGroupResp(greMsg: ClientToGREMessage) {
        val gameSession = session as? GameOps
        val pendingPrompt =
            gameSession
                ?.gameBridge
                ?.seat(SeatId(seatId))
                ?.prompt
                ?.getPendingPrompt()
        if (pendingPrompt != null) {
            gameSession.onGroupResp(greMsg)
        } else {
            mulliganHandler.onGroupResp(greMsg)
        }
    }

    /** Dispatch a post-handshake game action against the live [Connected] session. */
    @Suppress("CyclomaticComplexMethod", "ElseCaseInsteadOfExhaustiveWhen")
    private fun dispatchToSession(greMsg: ClientToGREMessage) {
        val s =
            connected?.session ?: run {
                when (greMsg.type) {
                    // Cosmetic relay / post-game ack — harmless without a session.
                    ClientMessageType.Uimessage_a39e -> {}
                    ClientMessageType.CheckpointReq ->
                        log.info("Match Door GRE: CheckpointReq (post-game acknowledgement)")
                    else -> log.warn("Match Door GRE: {} before session established", greMsg.type)
                }
                return
            }

        when (greMsg.type) {
            ClientMessageType.SetSettingsReq_097b -> s.onSettings(greMsg)

            ClientMessageType.ConcedeReq_097b -> {
                log.info("Match Door GRE: concede")
                s.onConcede()
            }

            ClientMessageType.PerformActionResp_097b -> s.onPerformAction(greMsg)

            ClientMessageType.DeclareAttackersResp_097b -> s.onDeclareAttackers(greMsg)

            // SubmitAttackersReq: client race condition may send on Familiar channel.
            // FamiliarSession no-ops handle it.
            ClientMessageType.SubmitAttackersReq -> s.onDeclareAttackers(greMsg)

            ClientMessageType.DeclareBlockersResp_097b -> s.onDeclareBlockers(greMsg)

            // Same pattern as SubmitAttackersReq.
            ClientMessageType.SubmitBlockersReq -> s.onDeclareBlockers(greMsg)

            ClientMessageType.SelectTargetsResp_097b -> s.onSelectTargets(greMsg)

            // SubmitTargetsReq: client's "Done" button for targeting (like SubmitAttackersReq for combat).
            ClientMessageType.SubmitTargetsReq -> s.onSubmitTargets(greMsg)

            ClientMessageType.EffectCostResp_097b -> s.onEffectCost(greMsg)

            ClientMessageType.CancelActionReq_097b -> s.onCancelAction(greMsg)

            ClientMessageType.SelectNresp -> s.onSelectN(greMsg)

            ClientMessageType.OrderResp_097b -> s.onOrderResp(greMsg)

            ClientMessageType.CastingTimeOptionsResp_097b -> s.onCastingTimeOptions(greMsg)

            ClientMessageType.SearchResp_097b -> s.onSearch(greMsg)

            ClientMessageType.AssignDamageResp_097b -> s.onAssignDamage(greMsg)

            ClientMessageType.OptionalActionResp -> s.onOptionalActionResp(greMsg)

            ClientMessageType.NumericInputResp_097b -> s.onNumericInputResp(greMsg)

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

    // --- Template-based senders (pre-mulligan) ---

    private fun sendRoomState() {
        val playerId = clientId.removeSuffix("_Familiar")
        val opponentName = "AI Opponent"
        val eventName = coordinator?.selectedEventName ?: "AIBotMatch"
        val msg = HandshakeMessages.roomState(matchId, playerId, opponentName, eventName, true)
        Tap.outboundTemplate("RoomState matchId=$matchId opponent=$opponentName")
        ProtoDump.dump(msg, "RoomState")
        output.send(msg)
    }

    private fun sendInitialBundle() {
        val activeSession = session ?: return
        if (activeSession is SpectatorSession) {
            sendInitialBundleOwned()
        } else {
            registry.ownerFor(matchId).reduce(::sendInitialBundleOwned)
        }
    }

    private fun sendInitialBundleOwned() {
        val s = session ?: return
        val bridge = registry.getMatch(matchId)?.bridge ?: return
        val gsId = s.counter.nextGsId()
        val seat = SeatId(seatId)
        val deckGrpIds = bridge.getDeckGrpIds(seat)
        val deck = GsmBuilder.buildDeckMessage(deckGrpIds, bridge.getCommanderGrpIds(seat))
        val (msg, nextMsgId) =
            HandshakeMessages.initialBundle(
                SeatId(seatId),
                matchId,
                s.counter.currentMsgId(),
                gsId,
                deck,
                bridge,
                dieRollWinner = bridge.dieRollWinner,
                includeStartingPlayerPrompt = !isSpectatorMode(),
                onInitialSnapshot = { snap ->
                    if (isSpectatorMode()) bridge.bundleCursor.lastSent = snap
                },
            )
        s.counter.setMsgId(nextMsgId)
        s.counter.markGameStateGsId(gsId)
        if (s !is SpectatorSession) {
            registry.ownerFor(matchId).observeOutbound(msg)
        }
        Tap.outboundTemplate("InitialBundle seat=$seatId")
        ProtoDump.dump(msg, "InitialBundle-seat$seatId")
        output.send(msg)
        if (s !is SpectatorSession) {
            bridge.activateInteractivePlayback()
        }
    }

    private fun onLocalPlayerConnected(bridge: GameBridge) {
        sendRoomState()
        registry.ownerFor(matchId).reduce {
            createAndRegisterMatchSession(bridge)
            mulliganHandler.seat1Hand = bridge.getHandGrpIds(SeatId(1))
            mulliganHandler.seat2Hand = bridge.getHandGrpIds(SeatId(2))
            log.info(
                "Match Door: seat {} connected, hands seat1={} seat2={}",
                seatId,
                mulliganHandler.seat1Hand,
                mulliganHandler.seat2Hand,
            )
            sendInitialBundleOwned()
        }
    }

    fun disconnected() {
        if (state === MatchHandlerState.Detached) return
        log.info("Match Door: client disconnected")
        if (isSpectatorMode() && isFamiliar) {
            log.info("Match Door: spectator familiar disconnected, leaving AI match active")
            return
        }
        registry.teardownMatch(
            matchId = matchId,
            reason = MatchTeardownReason.Disconnect,
            seatId = SeatId(seatId),
            recorder = session?.recorder,
            fallbackBridge = (session as? GameOps)?.gameBridge,
            expectedConnection = this,
        )
    }

    fun failed(cause: Throwable) {
        if (state === MatchHandlerState.Detached) {
            output.close()
            return
        }
        log.error("Match Door error: {}", cause.message, cause)
        registry.teardownMatch(
            matchId = matchId,
            reason = MatchTeardownReason.Exception,
            seatId = SeatId(seatId),
            recorder = session?.recorder,
            fallbackBridge = (session as? GameOps)?.gameBridge,
            expectedConnection = this,
        )
        output.close()
    }

    internal fun detachAfterTeardown() {
        state = MatchHandlerState.Detached
    }

    /**
     * MatchHandler connection lifecycle.
     *
     * Identity is mutable and tentative during [Handshaking] (accrues across
     * AuthenticateRequest then ClientToMatchDoorConnectRequest). Once the session is
     * built it freezes into [Connected], where `session` is non-null — so every
     * post-handshake dispatch typechecks against a session that must exist instead of
     * a `session?.onX(...)` safe-call that lies about an impossible null. The
     * placeholder identity defaults live only in [Handshaking]; [Connected] carries
     * the resolved values; [Detached] carries no match authority.
     */
    private sealed class MatchHandlerState {
        data object Detached : MatchHandlerState()

        class Handshaking : MatchHandlerState() {
            /** Tentative until ClientToMatchDoorConnectRequest carries a non-empty matchId. */
            var matchId: String = "forge-match-1"

            /** Tentative until AuthenticateRequest; otherwise a seat-derived fallback. */
            var clientId: String = "forge-player-1"

            /** Tentative until the connect-request GRE reports a positive systemSeatId. */
            var seatId: Int = 1

            var isFamiliar: Boolean = false
        }

        class Connected(
            val matchId: String,
            val clientId: String,
            val seatId: Int,
            val isFamiliar: Boolean,
            var session: SessionOps,
        ) : MatchHandlerState()
    }

    private data class SeatDecks(
        val seat1: String,
        val seat2: String,
    )

    private fun resolveSeatDecks(): SeatDecks {
        val randomDecks = spectatorRandomDecksIfEnabled()
        val runtimeMatchConfig = resolveRuntimeMatchConfig()
        val seat1Deck = resolveSeat1Deck(randomDecks, runtimeMatchConfig)
        return SeatDecks(
            seat1 = seat1Deck,
            seat2 = resolveSeat2Deck(randomDecks, runtimeMatchConfig, seat1Deck),
        )
    }

    /**
     * Resolve seat 1 deck: FD stored a deckId from 612 → look it up in player.db
     * and convert grpIds → card names for Forge engine.
     */
    private fun resolveSeat1Deck(
        randomDecks: Pair<String, String>?,
        runtimeMatchConfig: RuntimeMatchConfig?,
    ): String {
        randomDecks?.first?.let {
            log.info("Match Door: spectator seat 1 deck from random pair")
            return convertArenaCardsToDeckText(it)
        }
        runtimeMatchConfig?.seat1Deck?.takeIf { it.isNotBlank() }?.let {
            log.info("Match Door: seat 1 deck from runtime override")
            return it
        }
        val deckId = coordinator?.selectedDeckId
        if (deckId != null) {
            val cardsJson = coordinator.resolveDeckJson(deckId)
            if (cardsJson != null) {
                log.info("Match Door: seat 1 deck from DB deckId={}", deckId)
                return convertArenaCardsToDeckText(cardsJson)
            }
            log.warn("Match Door: deckId {} not in DB", deckId)
        }
        // Fallback: pick first deck from DB (AI bot events don't send deckId)
        val fallback = coordinator?.resolveFirstDeck()
        if (fallback != null) {
            log.info("Match Door: seat 1 using fallback deck (no deckId from client)")
            return convertArenaCardsToDeckText(fallback)
        }
        error("No deck selected for seat 1 — select a deck in the Arena client before queuing")
    }

    /**
     * Resolve seat 2 (AI) deck.
     *
     * Priority:
     *   1. Pod-bot deck for the active event (Quick Draft → one of the 7 bots that
     *      drafted alongside the player). Falls through if the event has no pod.
     *   2. AI deck name from `matchConfig.game.aiDeck` looked up in player.db.
     *   3. Mirror seat 1's deck.
     */
    private fun resolveSeat2Deck(
        randomDecks: Pair<String, String>?,
        runtimeMatchConfig: RuntimeMatchConfig?,
        seat1Deck: String,
    ): String {
        randomDecks?.second?.let {
            log.info("Match Door: spectator seat 2 deck from random pair")
            return convertArenaCardsToDeckText(it)
        }
        runtimeMatchConfig?.seat2Deck?.takeIf { it.isNotBlank() }?.let {
            log.info("Match Door: seat 2 deck from runtime override")
            return it
        }
        val event = coordinator?.selectedEventName
        if (event != null) {
            val podJson = coordinator.resolveOpponentDeckJson(event)
            if (podJson != null) {
                log.info("Match Door: seat 2 deck from draft pod event={}", event)
                return convertArenaCardsToDeckText(podJson)
            }
        }

        val aiDeckName = matchConfig.game.aiDeck
        if (aiDeckName != null && coordinator != null) {
            val cardsJson = coordinator.resolveDeckJsonByName(aiDeckName)
            if (cardsJson != null) {
                log.info("Match Door: seat 2 deck from DB name={}", aiDeckName)
                return convertArenaCardsToDeckText(cardsJson)
            }
            log.warn("Match Door: AI deck '{}' not in DB, mirroring seat 1", aiDeckName)
        }
        return seat1Deck
    }

    private fun spectatorRandomDecksIfEnabled(): Pair<String, String>? {
        if (!isSpectatorMode()) return null
        if (!matchConfig.game.aiDeck.equals("random", ignoreCase = true)) return null
        return spectatorRandomDecks()
    }

    private fun spectatorRandomDecks(): Pair<String, String>? {
        spectatorRandomDeckPair?.let { return it }
        val pair = coordinator?.resolveRandomDeckPairJson()
        spectatorRandomDeckPair = pair
        if (pair == null) log.warn("Match Door: spectator random deck pair unavailable, falling back to selected deck")
        return pair
    }

    /** Parse Arena cards JSON → Forge deck text (qty + name per line). Delegates to [DeckConverter]. */
    private fun convertArenaCardsToDeckText(cardsJson: String): String {
        val obj = lenientJson.parseToJsonElement(cardsJson).jsonObject
        val mainDeck = parseDeckSection(obj, "MainDeck")
        val sideboard = parseDeckSection(obj, "Sideboard")
        val commandZone = parseDeckSection(obj, "CommandZone")
        return DeckConverter.toDeckText(mainDeck, sideboard, commandZone, cardRepository::findNameByGrpId)
    }

    /** A runtime launch overrides the selected event; event selection remains the client-match fallback. */
    private fun resolveGameVariant(): String? = runtimeGameVariant(resolveRuntimeMatchConfig(), coordinator?.selectedEventName)

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

/** Runtime spectator launches own their format; other matches retain event-selected format inference. */
internal fun runtimeGameVariant(
    runtimeConfig: RuntimeMatchConfig?,
    selectedEventName: String?,
): String? {
    if (runtimeConfig?.spectatorMode == true) return runtimeConfig.gameVariant
    return if (selectedEventName?.contains("Brawl", ignoreCase = true) == true) "brawl" else null
}
