package leyline.match

import leyline.bridge.types.ClientAutoPassState
import leyline.bridge.types.PlayerLossCause
import leyline.bridge.types.SeatId
import leyline.domain.service.MatchCoordinator
import leyline.game.EngineCut
import leyline.game.EngineCutCheckpoint
import leyline.game.annotations.AnnotationLossReason
import leyline.game.bundle.ActionCatalogPlan
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.MessageCounter
import leyline.game.state.GameBridge
import leyline.game.state.GameResetCommand
import leyline.infra.MessageSink
import leyline.protocol.HandshakeMessages
import leyline.protocol.ProtoDump
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Game orchestration session — thin dispatcher for post-mulligan game logic.
 *
 * Delegates combat flows to [CombatHandler], targeting to [TargetingHandler],
 * and the auto-pass loop to [AutoPassEngine]. Owns message
 * sending, and Familiar mirroring.
 *
 * The owner drains engine-cut values, then compiles, sequences, commits, and
 * sends each resulting bundle.
 *
 * Transport-agnostic: sends messages through [MessageSink].
 * [MatchHandler] creates one per connection and delegates GRE messages here.
 */
@Suppress("LargeClass")
class MatchSession(
    val connection: ConnectionState,
    override val gameBridge: GameBridge,
    val paceDelayMs: Long = 200L,
    override var counter: MessageCounter = gameBridge.messageCounter,
) : GameOps {
    private val log = LoggerFactory.getLogger(MatchSession::class.java)

    override val seatId: SeatId get() = connection.seatId
    override val matchId: String get() = connection.matchId
    val sink: MessageSink get() = connection.sink
    val registry: MatchRegistry get() = connection.registry
    override val recorder: MatchRecorder? get() = connection.recorder
    val coordinator: MatchCoordinator? get() = connection.coordinator

    /** Client player ID — delegate; mutable on connection. */
    var playerId: String
        get() = connection.playerId
        set(value) {
            connection.playerId = value
        }

    /** Client SetSettingsReq state — delegate; mutable on connection. */
    var clientSettings: SettingsMessage?
        get() = connection.clientSettings
        set(value) {
            connection.clientSettings = value
        }

    val autoPassState: ClientAutoPassState get() = connection.autoPassState

    private val owner get() = connection.owner
    private val outbox =
        MatchSessionOutbox(
            owner,
            seatId,
            sink,
            counter,
            recorder,
            ::familiarPeer,
        )
    private val autoAdvanceRequested = AtomicBoolean(false)
    private val autoAdvanceRunning = AtomicBoolean(false)
    private val autoAdvanceClosed = AtomicBoolean(false)
    private val autoAdvanceRequest: (String) -> Unit = { reason -> requestAutoAdvance(reason) }
    private var gameOverCommitted = false

    private fun reduceActive(action: () -> Unit) {
        if (autoAdvanceClosed.get()) return
        owner.reduce {
            owner.assertOwnerThread()
            if (!autoAdvanceClosed.get()) {
                drainPlaybackOwned()
                action()
            }
        }
    }

    /**
     * Game + bridge bound at construction. MatchSession is per-game; on
     * puzzle hot-swap MatchHandler builds a fresh instance for the new
     * game, so this snapshot stays valid for the session's lifetime.
     */
    override val bundleBuilder: BundleBuilder = BundleBuilder(gameBridge, matchId, seatId.value)

    private val engineCutAwaiter =
        object : EngineCutAwaiter {
            override fun awaitPriority(): Boolean = awaitPriorityOwned(gameBridge.priorityWaitMs)

            override fun awaitPriorityWithTimeout(timeoutMs: Long): Boolean = awaitPriorityOwned(timeoutMs)

            override fun awaitActionPriority(): Boolean = awaitActionPriorityOwned()
        }

    internal val ctx: SessionContext = SessionContext(gameBridge, engineCutAwaiter, owner::engineObservation)

    init {
        gameBridge.configureAutoPass(autoPassState)
    }

    /** Sub-handlers for combat, targeting, optional actions, and auto-pass flows. */
    private val combatHandler =
        CombatHandler(
            sink = this,
            counters = this,
            bundles = this,
            pacing = this,
            lastPromptGsId = owner::lastPromptGsId,
            ctx = ctx,
        )
    private val targetingHandler =
        TargetingHandler(
            sink = this,
            counters = this,
            bundles = this,
            ctx = ctx,
            autoPassState = autoPassState,
        )
    private val optionalActionHandler =
        OptionalActionHandler(
            sink = this,
            counters = this,
            ctx = ctx,
        )
    private val numericInputHandler =
        NumericInputHandler(
            sink = this,
            counters = this,
            ctx = ctx,
        )
    private val autoPassEngine =
        AutoPassEngine(
            sink = this,
            counters = this,
            bundles = this,
            combatHandler = combatHandler,
            targetingHandler = targetingHandler,
            optionalActionHandler = optionalActionHandler,
            numericInputHandler = numericInputHandler,
            ctx = ctx,
            autoPassState = autoPassState,
        )
    private val actionPerformer =
        ActionPerformer(
            sink = this,
            counters = this,
            matchRecorder = recorder,
            bundles = this,
            targetingHandler = targetingHandler,
            autoPassEngine = autoPassEngine,
            autoPassState = autoPassState,
            lastPromptGsId = owner::lastPromptGsId,
            ctx = ctx,
        )

    init {
        gameBridge.autoAdvanceRequester = autoAdvanceRequest
    }

    // --- Public entry points (called by MatchHandler) ---

    /**
     * After keep: wait for engine to reach priority, send real game state bundle.
     * Then auto-pass through phases where only Pass is available.
     */
    override fun onMulliganKeep() =
        reduceActive {
            log.info("MatchSession: waiting for engine to reach priority after keep")

            ctx.engine.awaitPriority()

            // The owner wait already drained every preceding playback value.
            // Allocate the phase transition next on the same causal gsId chain.
            val bb = bundleBuilder
            val result = bb.phaseTransitionDiff(ctx.observation(), counter)
            sendBundle(result)

            // Seed state snapshot for subsequent diff computation.
            val snap1 = ctx.snapshot().withFrameIdentity(matchId, counter.currentGsId())
            bb.cursor.lastSent = snap1

            // Auto-pass through phases where human has no real actions
            autoPassEngine.autoPassAndAdvance()
        }

    /**
     * Puzzle start: seed snapshot, enter auto-pass loop.
     * Similar to [onMulliganKeep] but without mulligan seeding or phaseTransitionDiff
     * — the puzzle initial bundle already sent a Full GSM with the board state.
     */

    /**
     * Trigger autoPassAndAdvance without submitting an action first.
     * Used by tests when the engine is already at a combat phase and
     * CombatHandler needs to send the prompt (DeclareBlockersReq).
     */
    fun triggerAutoPass() =
        reduceActive {
            autoPassEngine.autoPassAndAdvance()
        }

    /**
     * Serialize an external game-logic entrant with inbound handlers and auto-advance.
     *
     * Debug control and pre-game response routing use this boundary so they cannot
     * mutate engine or projection state behind the session's current authority.
     * Work queued by a displaced generation returns `null` instead of entering the
     * replacement.
     */
    fun <T> withSessionAuthority(action: () -> T): T? {
        if (autoAdvanceClosed.get()) return null
        return owner.reduce {
            if (autoAdvanceClosed.get()) null else action()
        }
    }

    /** Project and correlate the current owner-observed executable action window once. */
    fun currentObservedActionCatalog(gameStateId: Int): Pair<ActionsAvailableReq, ActionCatalogPlan?> {
        owner.assertOwnerThread()
        val observation = ctx.observation()
        val projection = bundleBuilder.projectObservedActions(observation)
        val window = observation.preparedPriorityWindows[seatId]
        val catalog = window?.let { ActionCatalogPlan(it.actionId, gameStateId, projection.offers) }
        return projection.actions to catalog
    }

    /**
     * Replace this session with a fresh one bound to a hot-swapped puzzle game.
     *
     * The connection (sink, identity, settings, autoPassState, owner) and
     * the bridge instance survive — only the `Game` inside the bridge changes,
     * and a new MatchSession is built with handlers ctx-bound to the new game.
     *
     * Reduced by [connection]'s owner so concurrent inbound messages cannot
     * interleave with the swap.
     *
     * @return Pair of (new session, ids the client should delete from its view).
     */
    fun replaceForPuzzle(command: GameResetCommand): Pair<MatchSession, List<Int>> =
        owner.reduce {
            owner.assertOwnerThread()
            owner.clearEngineObservation()
            val deletedIds = command.reset(gameBridge)
            val replacement = MatchSession(connection, gameBridge, paceDelayMs, counter)
            registry.registerSession(matchId, seatId, replacement)
            // Update the per-channel handler so future inbound GRE messages dispatch
            // to the new session. Without this, MatchHandler keeps a stale reference
            // and the next PerformActionResp builds a Diff against unrelated game
            // state, producing spurious diffDeletedInstanceIds.
            registry.getConnection(matchId, seatId)?.session = replacement
            close()
            replacement to deletedIds
        }

    override fun onPuzzleStart() =
        reduceActive {
            if (!preparePuzzleStart()) return@reduceActive

            // Auto-pass through phases where human has no real actions
            autoPassEngine.autoPassAndAdvance()
        }

    internal fun preparePuzzleStart(): Boolean {
        owner.assertOwnerThread()
        // FamiliarSession inherits a no-op onPuzzleStart from SessionOps, so this
        // path only fires for MatchSession. Warn if somehow called for a non-human
        // MatchSession — it would consume the human seat's pending priority via the
        // shared ActionBridge, advancing the engine past Main1.
        val humanSeat = gameBridge.seating.humanSeat
        if (seatId != humanSeat) {
            log.warn("MatchSession: onPuzzleStart called for seat {} — expected humanSeat {}", seatId.value, humanSeat.value)
            return false
        }

        log.info("MatchSession: puzzle start, seeding snapshot and entering game loop")

        // Seed state snapshot for subsequent diff computation.
        // The puzzle initial bundle already sent the Full GSM, so the cursor
        // needs a matching snapshot for the first Diff to be correct.
        val snap2 = ctx.snapshot().withFrameIdentity(matchId, counter.currentGsId())
        bundleBuilder.cursor.lastSent = snap2
        return true
    }

    /**
     * Handle a client action (land play, spell cast, pass) and advance the engine.
     * Delegates to [ActionPerformer] — this method is the match-owner boundary
     * and context resolver.
     */
    override fun onPerformAction(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            actionPerformer.perform(greMsg)
        }

    /** Handle DeclareAttackersResp — delegates to [CombatHandler]. */
    override fun onDeclareAttackers(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            combatHandler.onDeclareAttackers(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    /** Handle DeclareBlockersResp — delegates to [CombatHandler]. */
    override fun onDeclareBlockers(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            combatHandler.onDeclareBlockers(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    /** Handle AssignDamageResp — delegates to [CombatHandler]. */
    override fun onAssignDamage(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            combatHandler.onAssignDamage(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    /** Handle OptionalActionResp — delegates to [OptionalActionHandler]. */
    override fun onOptionalActionResp(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            optionalActionHandler.onOptionalActionResp(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    /** Handle NumericInputResp — delegates to [NumericInputHandler]. */
    override fun onNumericInputResp(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            numericInputHandler.onNumericInputResp(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    /** Handle SelectTargetsResp — delegates to [TargetingHandler]. */
    override fun onSelectTargets(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            targetingHandler.onSelectTargets(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    /** Handle SubmitTargetsReq — finalizes two-phase targeting. */
    override fun onSubmitTargets(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            targetingHandler.onSubmitTargets { autoPassEngine.autoPassAndAdvance() }
        }

    /** Handle SelectNResp — delegates to [TargetingHandler]. */
    override fun onSelectN(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            targetingHandler.onSelectN(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    override fun onOrderResp(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            targetingHandler.onOrderResp(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    override fun onEffectCost(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            targetingHandler.onEffectCost(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    /** Handle GroupResp for surveil/scry — delegates to [TargetingHandler]. */
    override fun onGroupResp(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            targetingHandler.onGroupResp(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    /** Handle CastingTimeOptionsResp — delegates to [TargetingHandler]. */
    override fun onCastingTimeOptions(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            targetingHandler.onCastingTimeOptions(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    /** Handle SearchResp — delegates to [TargetingHandler]. */
    override fun onSearch(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            val itemsFound = greMsg.searchResp?.itemsFoundList ?: emptyList()
            targetingHandler.onSearchResp(itemsFound) { autoPassEngine.autoPassAndAdvance() }
        }

    private fun withValidResponse(
        greMsg: ClientToGREMessage,
        block: () -> Unit,
    ): Unit =
        reduceActive {
            if (!ResponseEnvelopeGuard.rejectMismatch(greMsg, owner.lastPromptMsgId(), counter, this)) block()
        }

    /**
     * Handle CancelActionReq — player cancelled targeting (backed out of spell cast).
     *
     * Submits an empty target list to the pending prompt, which causes the engine
     * to return `TargetSelectionResult(false, false)` → spell targeting fails →
     * engine unwinds the cast (removes from stack, returns mana).
     */
    override fun onCancelAction(greMsg: ClientToGREMessage): Unit =
        reduceActive {
            // During combat declaration, cancel means "pass combat" (submit empty attackers).
            if (combatHandler.pendingLegalAttackers.isNotEmpty()) {
                combatHandler.onCancelAttackers { autoPassEngine.autoPassAndAdvance() }
                return@reduceActive
            }
            targetingHandler.onCancelAction { autoPassEngine.autoPassAndAdvance() }
        }

    /** Handle concede: send game-over sequence, then route through centralized teardown. */
    override fun onConcede() =
        reduceActive {
            sendGameOver(ResultReason.Concede)
        }

    /** Handle SetSettingsReq: merge settings, apply stops to PhaseStopProfile, echo response. */
    override fun onSettings(greMsg: ClientToGREMessage) =
        reduceActive {
            val reqSettings = greMsg.setSettingsReq
            val incoming = reqSettings.settings
            log.info(
                "MatchSession: SetSettingsReq (stops={} transientStops={})",
                incoming.stopsCount,
                incoming.transientStopsCount,
            )

            // Merge incoming delta into accumulated clientSettings (client sends only changed fields).
            clientSettings = mergeSettings(clientSettings, incoming)

            // Apply stop changes to the live PhaseStopProfile so the engine
            // respects client's phase ladder toggles — both Team and Opponents scopes.
            applyStopsToProfile(incoming)

            // Track autoPassOption / stackAutoPassOption for priority decisions.
            autoPassState.update(incoming)
            log.debug(
                "MatchSession: autoPassOption={} stackAutoPassOption={}",
                autoPassState.autoPassOption,
                autoPassState.stackAutoPassOption,
            )

            val (msg, nextMsgId) =
                HandshakeMessages.settingsResp(
                    seatId,
                    counter.currentMsgId(),
                    counter.currentGsId(),
                    clientSettings,
                )
            counter.setMsgId(nextMsgId)
            ProtoDump.dump(msg, "SettingsResp")
            sendMatchProgressOwned(msg)
        }

    /**
     * Map client [SettingsMessage] stops + transientStops to [PhaseStopProfile] updates.
     *
     * Team scope → human player's own-turn stops.
     * Opponents scope → AI player's turn stops (seat math: if human=1, AI=2).
     *
     * TransientStops have the same [Stop] shape; v1 treats them as persistent
     * (no one-shot consume yet).
     */
    private fun applyStopsToProfile(settings: SettingsMessage) {
        val bridge = gameBridge
        if (settings.clearAllStops == SettingStatus.Set || settings.clearAllYields == SettingStatus.Set) {
            autoPassState.clearOpponentStops()
            log.debug("MatchSession: clearAll — clearAllStops={} clearAllYields={}", settings.clearAllStops, settings.clearAllYields)
        }
        val update = bridge.applyPhaseStops(seatId, settings)
        update.opponentEnabled.forEach { autoPassState.setOpponentStop(it, true) }
        update.opponentDisabled.forEach { autoPassState.setOpponentStop(it, false) }
    }

    /**
     * Merge incoming settings delta into accumulated settings.
     * Stops keyed by (stopType, appliesTo) — incoming overrides existing.
     * Clear status marks a stop as disabled but does not remove it from the set.
     */
    companion object {
        fun mergeSettings(
            existing: SettingsMessage?,
            incoming: SettingsMessage,
        ): SettingsMessage {
            if (existing == null) return incoming
            val merged = existing.toBuilder()

            // Merge stops: build a map keyed by (stopType, appliesTo), incoming overrides existing
            val stopMap = linkedMapOf<Pair<Int, Int>, Stop>()
            for (stop in existing.stopsList) {
                stopMap[stop.stopType.number to stop.appliesTo.number] = stop
            }
            for (stop in incoming.stopsList) {
                stopMap[stop.stopType.number to stop.appliesTo.number] = stop
            }
            merged.clearStops().addAllStops(stopMap.values)

            // Merge transientStops the same way
            val transMap = linkedMapOf<Pair<Int, Int>, Stop>()
            for (stop in existing.transientStopsList) {
                transMap[stop.stopType.number to stop.appliesTo.number] = stop
            }
            for (stop in incoming.transientStopsList) {
                transMap[stop.stopType.number to stop.appliesTo.number] = stop
            }
            merged.clearTransientStops().addAllTransientStops(transMap.values)

            // Merge scalar fields only when incoming has non-default values
            if (incoming.autoPassOption != AutoPassOption.None_a465) {
                merged.autoPassOption = incoming.autoPassOption
            }
            if (incoming.stackAutoPassOption != AutoPassOption.None_a465) {
                merged.stackAutoPassOption = incoming.stackAutoPassOption
            }

            return merged.build()
        }
    }

    // --- Sending helpers ---

    /**
     * Build and send current game state + available actions from the live Forge engine.
     */
    override fun sendRealGameState(
        bridge: GameBridge,
        revealForSeat: Int?,
    ) = reduceActive {
        sendRealGameStateOwned(bridge, revealForSeat)
    }

    private fun sendRealGameStateOwned(
        bridge: GameBridge,
        revealForSeat: Int?,
    ) {
        if (bridge.seat(seatId).action.getPending() == null && !bridge.hasPendingNonActionInteraction()) {
            engineCutAwaiter.awaitActionPriority()
        }
        if (bridge.seat(seatId).action.getPending() == null) {
            sendBundle(bundleBuilder.stateOnlyDiff(ctx.snapshot(), counter))
            return
        }
        sendPriorityState(bridge, revealForSeat)
    }

    override fun sendPriorityState(bridge: GameBridge) =
        reduceActive {
            sendPriorityState(bridge, null)
        }

    private fun sendPriorityState(
        bridge: GameBridge,
        revealForSeat: Int?,
    ) {
        val bb = bundleBuilder
        val result = bb.postAction(ctx.observation(), counter, revealForSeat)

        // Warn on empty diffs — usually means the caller emitted a GSM at the wrong moment
        val gsm = result.messages.firstOrNull { it.hasGameStateMessage() }?.gameStateMessage
        // TODO: empty diff detection — disabled for now, many legitimate empty diffs exist
        //  (actions-only updates, phase transitions). Needs filtering by caller context.

        sendBundle(result)

        // Decision timer — client shows rope countdown while waiting for action
        if (bridge.matchConfig.game.timer) {
            val timer = bb.timerStart(counter)
            sendBundledGRE(timer.messages)
        }
    }

    /** Apply a [BundleBuilder.BundleResult]: tap-log and send. */
    override fun sendBundle(result: BundleBuilder.BundleResult) =
        reduceActive {
            sendBundleOwned(result)
        }

    private fun sendBundleOwned(result: BundleBuilder.BundleResult) {
        for (gre in result.messages) {
            if (gre.hasGameStateMessage()) Tap.outboundState(gre.gameStateMessage)
            if (gre.hasActionsAvailableReq()) Tap.outboundActions(gre.actionsAvailableReq)
        }
        sendBundledGRE(result.messages)
    }

    /**
     * Send game-over sequence: 3x GS Diff + IntermissionReq + MatchCompleted room state.
     *
     * Per protocol analysis (post-game), the full sequence is:
     * 1. Server sends 3x GSM (GameOver) + IntermissionReq
     * 2. Client responds with CheckpointReq (handled in MatchHandler)
     * 3. Server sends MatchGameRoomStateChangedEvent (MatchCompleted)
     *
     * We send MatchCompleted immediately after IntermissionReq rather than
     * waiting for CheckpointReq — the client tolerates this ordering and it
     * avoids needing cross-layer coordination between MatchHandler and MatchSession.
     */
    override fun sendGameOver(reason: ResultReason) =
        reduceActive {
            sendGameOverOwned(reason)
        }

    private fun sendGameOverOwned(reason: ResultReason) {
        if (gameOverCommitted) return
        val bridge = gameBridge
        val observation = ctx.observation()
        val humanWon = observation.runtimeFor(seatId).won
        val winningTeam = if (humanWon) 1 else 2
        val losingPlayerSeatId = if (humanWon) 2 else 1
        val lossReason =
            annotationLossReasonFor(
                reason,
                observation.runtimeFor(SeatId(losingPlayerSeatId)).lossCause,
            )

        // If there are pending events (e.g. mana-ability sacrifice during resolution),
        // build a final diff GSM to emit those annotations before the game-over bundle.
        // This mirrors client behavior, which sends a resolution GSM before GameComplete.
        val bb = bundleBuilder
        if (observation.runtimeFor(seatId).isGameOver && observation.hasPendingEvents) {
            val resolutionBundle = bb.stateOnlyDiff(observation.snapshot, counter)
            sendBundledGRE(resolutionBundle.messages)
            log.debug("sendGameOver: flushed {} pending events in pre-game-over diff", resolutionBundle.messages.size)
        }

        val result =
            bb.gameOverBundle(
                winningTeam,
                counter,
                reason = reason,
                losingPlayerSeatId = losingPlayerSeatId,
                lossReason = lossReason,
                snapshot = observation.snapshot,
            )
        sendBundledGRE(result.messages)
        log.info("MatchSession: sent game-over GRE sequence (winner=team{}, reason={})", winningTeam, reason)

        // Send MatchCompleted room state — triggers the client's result screen
        val matchCompletedMsg = HandshakeMessages.matchCompleted(matchId, winningTeam, playerId, reason)
        sendMatchProgressOwned(matchCompletedMsg)
        log.info("MatchSession: sent MatchCompleted room state")
        gameOverCommitted = true

        outbox.afterDrained {
            // Notify coordinator (e.g. CourseService for sealed events)
            try {
                coordinator?.reportMatchResult(matchId, humanWon)
            } catch (e: Exception) {
                log.warn("MatchSession: reportMatchResult failed: {}", e.message)
            }

            // Trigger post-game analysis
            recorder?.run {
                markGameOver()
            }

            registry.teardownMatch(
                matchId = matchId,
                reason = if (reason == ResultReason.Concede) MatchTeardownReason.Concede else MatchTeardownReason.GameOver,
                seatId = seatId,
                recorder = recorder,
                fallbackBridge = bridge,
            )
        }
    }

    // --- Low-level helpers ---

    /**
     * Send multiple GRE messages bundled in one GreToClientEvent + mirror to peer.
     *
     * The owner records every prompt-bearing GRE before delivery so response
     * validation and staleness predicates share one ordered horizon.
     */
    override fun sendBundledGRE(messages: List<GREToClientMessage>) =
        reduceActive {
            sendBundledGREOwned(messages)
        }

    override fun sendMatchProgress(message: MatchServiceToClientMessage) =
        reduceActive {
            sendMatchProgressOwned(message)
        }

    private fun sendMatchProgressOwned(message: MatchServiceToClientMessage) {
        outbox.sendRaw(message)
    }

    private fun sendBundledGREOwned(messages: List<GREToClientMessage>) {
        drainPlaybackOwned()
        sendBundledGREDirect(messages, mirror = true)
    }

    override fun drainPlayback(): Boolean {
        owner.assertOwnerThread()
        return drainPlaybackOwned()
    }

    private fun awaitPriorityOwned(timeoutMs: Long): Boolean {
        owner.assertOwnerThread()
        val checkpoint =
            gameBridge.awaitPriorityCut(timeoutMs)
                ?: run {
                    drainPlaybackOwned()
                    return false
                }
        drainEngineCutsThrough(checkpoint)
        return true
    }

    private fun awaitActionPriorityOwned(): Boolean {
        owner.assertOwnerThread()
        val checkpoint =
            gameBridge.awaitActionPriorityCut()
                ?: run {
                    drainPlaybackOwned()
                    return false
                }
        drainEngineCutsThrough(checkpoint)
        return true
    }

    override fun awaitEnginePriority(): Boolean = engineCutAwaiter.awaitPriority()

    override fun awaitEnginePriorityWithTimeout(timeoutMs: Long): Boolean = engineCutAwaiter.awaitPriorityWithTimeout(timeoutMs)

    internal fun ensureEngineObservation() {
        owner.assertOwnerThread()
        if (owner.engineObservation() == null) {
            check(awaitEnginePriority()) { "Engine observation did not become available" }
        }
    }

    private fun drainPlaybackOwned(): Boolean {
        owner.assertOwnerThread()
        val checkpoint = gameBridge.latestEngineCutCheckpoint()
        return drainEngineCutsThrough(checkpoint)
    }

    private fun drainEngineCutsThrough(checkpoint: EngineCutCheckpoint): Boolean {
        var delivered = false
        while (true) {
            val cut = gameBridge.peekEngineCutThrough(checkpoint) ?: break
            if (cut !is EngineCut.Observation) {
                owner.observeEngine((cut as EngineCut.InteractionReady).observation)
                gameBridge.acknowledgeEngineCut(cut)
                continue
            }
            owner.observeEngine(cut.value.observation)
            val results = bundleBuilder.playbackYield(cut.value, counter)
            gameBridge.acknowledgeEngineCut(cut)
            for (result in results) {
                paceBeforePlaybackDelivery(delivered)
                sendBundledGREDirect(result.messages, mirror = true)
                delivered = true
            }
            if (gameBridge.consumePromptTimeoutNeedsAutoAdvance()) {
                requestAutoAdvance("prompt timeout playback committed")
            }
        }
        return delivered
    }

    override fun sendSeatGRE(messages: List<GREToClientMessage>) =
        reduceActive {
            sendBundledGREDirect(messages, mirror = false)
        }

    private fun sendBundledGREDirect(
        messages: List<GREToClientMessage>,
        mirror: Boolean,
    ) {
        outbox.sendGre(messages, mirror)
    }

    private fun requestAutoAdvance(reason: String) {
        if (autoAdvanceClosed.get()) return
        autoAdvanceRequested.set(true)
        if (!autoAdvanceRunning.compareAndSet(false, true)) return

        val accepted =
            owner.enqueue {
                try {
                    owner.assertOwnerThread()
                    drainPlaybackOwned()
                    do {
                        if (autoAdvanceClosed.get()) return@enqueue
                        autoAdvanceRequested.set(false)
                        if (!ctx.runtime(seatId).hasPlayer) return@enqueue
                        log.debug("MatchSession: auto-advance pump ({})", reason)
                        autoPassEngine.autoPassAndAdvance()
                    } while (autoAdvanceRequested.get() && !autoAdvanceClosed.get())
                } catch (t: Throwable) {
                    log.warn("MatchSession: auto-advance pump failed: {}", t.message, t)
                } finally {
                    autoAdvanceRunning.set(false)
                    if (autoAdvanceRequested.get() && !autoAdvanceClosed.get()) requestAutoAdvance("reschedule")
                }
            }
        if (!accepted) {
            autoAdvanceRunning.set(false)
        }
    }

    fun close() {
        if (!retire()) return
        owner.reduce {
            owner.assertOwnerThread()
            finishRetirement()
        }
    }

    internal fun retireBeforeOwnerClose() {
        retire()
    }

    internal fun finishRetirementAfterOwnerClose() {
        finishRetirement()
    }

    private fun retire(): Boolean {
        if (!autoAdvanceClosed.compareAndSet(false, true)) return false
        autoAdvanceRequested.set(false)
        return true
    }

    private fun finishRetirement() {
        outbox.close()
        if (gameBridge.autoAdvanceRequester === autoAdvanceRequest) {
            gameBridge.autoAdvanceRequester = null
        }
    }

    private fun familiarPeer(): FamiliarSession? {
        if (seatId != gameBridge.seating.humanSeat) return null
        val peer = registry.getPeer(matchId, seatId) ?: return null
        return peer as? FamiliarSession
    }

    /** Pacing delay — skipped when paceDelayMs == 0 (tests). */
    override fun paceDelay(multiplier: Int) {
        val delay = paceDelayMs * multiplier
        if (delay > 0) Thread.sleep(delay)
    }
}

internal fun Pacing.paceBeforePlaybackDelivery(hasDelivered: Boolean) {
    if (hasDelivered) paceDelay(1)
}

internal fun annotationLossReasonFor(
    resultReason: ResultReason,
    lossState: PlayerLossCause?,
): AnnotationLossReason =
    if (resultReason == ResultReason.Concede) {
        AnnotationLossReason.Concede
    } else {
        when (lossState) {
            PlayerLossCause.LifeTotal -> AnnotationLossReason.LifeTotal
            PlayerLossCause.Poison -> AnnotationLossReason.Poison
            PlayerLossCause.Milled -> AnnotationLossReason.DrawFromEmptyLibrary
            PlayerLossCause.Concede -> AnnotationLossReason.Concede
            PlayerLossCause.Other,
            null,
            -> AnnotationLossReason.LifeTotal
        }
    }
