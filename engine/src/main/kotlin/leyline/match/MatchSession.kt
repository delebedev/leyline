package leyline.match

import forge.game.player.GameLossReason
import leyline.bridge.coord.GameOverIntent
import leyline.bridge.types.SeatId
import leyline.domain.service.MatchCoordinator
import leyline.game.annotations.AnnotationLossReason
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.MessageCounter
import leyline.game.bundle.PROMPT_GRE_TYPES
import leyline.game.bundle.markIfPrompt
import leyline.game.state.GameBridge
import leyline.infra.MessageSink
import leyline.protocol.HandshakeMessages
import leyline.protocol.ProtoDump
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Game orchestration session — thin dispatcher for post-mulligan game logic.
 *
 * Delegates combat flows to [CombatHandler], targeting to [TargetingHandler],
 * and the auto-pass loop to [AutoPassEngine]. Owns the [sessionLock], message
 * sending, and Familiar mirroring.
 *
 * Protocol sequencing uses a shared [MessageCounter] — same instance is passed
 * to [GamePlayback][leyline.game.GamePlayback]. No seeding or
 * syncing needed.
 *
 * Transport-agnostic: sends messages through [MessageSink].
 * [MatchHandler] creates one per connection and delegates GRE messages here.
 */
class MatchSession(
    val connection: ConnectionState,
    override val gameBridge: GameBridge,
    val paceDelayMs: Long = 200L,
    override var counter: MessageCounter = gameBridge.messageCounter,
    private val deferNetworkAdvance: Boolean = false,
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

    private val sessionLock get() = connection.sessionLock
    private val autoAdvanceExecutor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "match-autoadvance-${matchId.take(8)}-${seatId.value}").apply { isDaemon = true }
        }
    private val autoAdvanceRequested = AtomicBoolean(false)
    private val autoAdvanceRunning = AtomicBoolean(false)
    private val autoAdvanceClosed = AtomicBoolean(false)
    private val pendingDeferredWork = AtomicInteger(0)

    @Volatile
    private var lastPrompt: GREToClientMessage? = null

    fun lastPromptMessage(): GREToClientMessage? = lastPrompt

    private val autopush: leyline.copilot.CopilotAutopush? by lazy {
        val dev = gameBridge.engineSettings.dev
        if (dev.copilotAutopush) leyline.copilot.CopilotAutopush(gameBridge, seatId, dev.copilotBridgeUrl) else null
    }

    private val autoAdvanceRequest: (String) -> Unit = { reason -> requestAutoAdvance(reason) }
    private val playbackDrainRequest: () -> Unit = { requestPlaybackDrain() }

    /**
     * Game + bridge bound at construction. MatchSession is per-game; on
     * puzzle hot-swap MatchHandler builds a fresh instance for the new
     * game, so this snapshot stays valid for the session's lifetime.
     */
    val ctx: SessionContext = SessionContext(requireNotNull(gameBridge.getGame()) { "MatchSession requires non-null game" }, gameBridge)

    override val bundleBuilder: BundleBuilder = BundleBuilder(gameBridge, matchId, seatId.value)

    /** Sub-handlers for combat, targeting, optional actions, and auto-pass flows. */
    val combatHandler =
        CombatHandler(
            sink = this,
            counters = this,
            pacing = this,
            ctx = ctx,
        )
    val targetingHandler =
        TargetingHandler(
            sink = this,
            counters = this,
            bundles = this,
            ctx = ctx,
        )
    val optionalActionHandler =
        OptionalActionHandler(
            sink = this,
            counters = this,
            ctx = ctx,
        )
    val numericInputHandler =
        NumericInputHandler(
            sink = this,
            counters = this,
            ctx = ctx,
        )
    private val orderInteractionHandler = OrderInteractionHandler(ctx)
    private val distributionInteractionHandler = DistributionInteractionHandler(ctx)
    private val groupingInteractionHandler = GroupingInteractionHandler(ctx)
    val autoPassEngine =
        AutoPassEngine(
            sink = this,
            counters = this,
            bundles = this,
            pacing = this,
            combatHandler = combatHandler,
            targetingHandler = targetingHandler,
            optionalActionHandler = optionalActionHandler,
            numericInputHandler = numericInputHandler,
            ctx = ctx,
            priorityPolicy = gameBridge.priorityPolicy,
        )
    val actionPerformer =
        ActionPerformer(
            sink = this,
            counters = this,
            matchRecorder = recorder,
            targetingHandler = targetingHandler,
            autoPassEngine = autoPassEngine,
            priorityPolicy = gameBridge.priorityPolicy,
            ctx = ctx,
        )

    init {
        gameBridge.autoAdvanceRequester = autoAdvanceRequest
        gameBridge.playbackDrainRequester = playbackDrainRequest
    }

    // --- Public entry points (called by MatchHandler) ---

    /**
     * After keep: wait for engine to reach priority, send real game state bundle.
     * Then auto-pass through phases where only Pass is available.
     */
    override fun onMulliganKeep() =
        synchronized(sessionLock) {
            val bridge = gameBridge
            log.info("MatchSession: waiting for engine to reach priority after keep")

            bridge.awaitPriority()

            // The priority presentation is still coordinator-owned and unpublished.
            // Replace it before draining the feed so prior AI batches retain their
            // order and the replacement receives the next shared game-state id.
            val pending = checkNotNull(bridge.seat(seatId).action.getPending()) { "Initial priority window was not published" }
            val humanTurn = ctx.game.phaseHandler.playerTurn == bridge.getPlayer(seatId)
            bridge.cutCoordinator.replaceWithPhaseTransition(pending.actionId, includePriorityPrompt = humanTurn)
            drainCoordinatorFeed()

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
        synchronized(sessionLock) {
            autoPassEngine.autoPassAndAdvance()
        }

    /**
     * Replace this session with a fresh one bound to a hot-swapped puzzle game.
     *
     * The connection (sink, identity, settings, sessionLock) and
     * the bridge instance survive — only the `Game` inside the bridge changes,
     * and a new MatchSession is built with handlers ctx-bound to the new game.
     *
     * Held under [connection.sessionLock] so concurrent inbound messages can't
     * interleave with the swap.
     *
     * @return Pair of (new session, ids the client should delete from its view).
     */
    fun replaceForPuzzle(puzzle: forge.gamemodes.puzzle.Puzzle): Pair<MatchSession, List<Int>> =
        synchronized(sessionLock) {
            val deletedIds = gameBridge.resetForPuzzle(puzzle)
            val replacement = MatchSession(connection, gameBridge, paceDelayMs, counter, deferNetworkAdvance)
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
        synchronized(sessionLock) {
            if (!preparePuzzleStart()) return@synchronized

            // Auto-pass through phases where human has no real actions
            autoPassEngine.autoPassAndAdvance()
        }

    internal fun preparePuzzleStart(): Boolean {
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

        return true
    }

    /**
     * Handle a client action (land play, spell cast, pass) and advance the engine.
     * Delegates to [ActionPerformer] — this method is just the session-lock boundary
     * and context resolver.
     */
    override fun onPerformAction(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            actionPerformer.perform(greMsg)
        }

    /** Handle DeclareAttackersResp — delegates to [CombatHandler]. */
    override fun onDeclareAttackers(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            combatHandler.onDeclareAttackers(greMsg) { advanceAfterAttackersSubmitted() }
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
            targetingHandler.onSubmitTargets(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    /** Handle SelectNResp — delegates to [TargetingHandler]. */
    override fun onSelectN(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            targetingHandler.onSelectN(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    override fun onOrderResp(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            orderInteractionHandler.onOrderResp(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    override fun onDistributionResp(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            distributionInteractionHandler.onDistributionResp(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    override fun onEffectCost(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            targetingHandler.onEffectCost(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    override fun onGroupResp(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            groupingInteractionHandler.onGroupResp(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    /** Handle CastingTimeOptionsResp — delegates to [TargetingHandler]. */
    override fun onCastingTimeOptions(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            targetingHandler.onCastingTimeOptions(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    /** Handle SearchResp — delegates to [TargetingHandler]. */
    override fun onSearch(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) {
            targetingHandler.onSearchResp(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    private fun withValidResponse(
        greMsg: ClientToGREMessage,
        block: () -> Unit,
    ): Unit =
        synchronized(sessionLock) {
            if (!ResponseEnvelopeGuard.rejectMismatch(greMsg, counter, this)) block()
        }

    private fun advanceAfterAttackersSubmitted() {
        val requester = gameBridge.autoAdvanceRequester
        if (deferNetworkAdvance && requester != null) {
            requester("attackers submitted")
            return
        }
        gameBridge.awaitPriority()
        autoPassEngine.autoPassAndAdvance()
    }

    /**
     * Handle CancelActionReq — player cancelled targeting (backed out of spell cast).
     *
     * Submits an empty target list to the pending prompt, which causes the engine
     * to return `TargetSelectionResult(false, false)` → spell targeting fails →
     * engine unwinds the cast (removes from stack, returns mana).
     */
    override fun onCancelAction(greMsg: ClientToGREMessage): Unit =
        synchronized(sessionLock) {
            // During combat declaration, cancel means "pass combat" (submit empty attackers).
            if (combatHandler.pendingLegalAttackers.isNotEmpty()) {
                combatHandler.onCancelAttackers { autoPassEngine.autoPassAndAdvance() }
                return
            }
            targetingHandler.onCancelAction(greMsg) { autoPassEngine.autoPassAndAdvance() }
        }

    /** Handle concede: send game-over sequence, then route through centralized teardown. */
    override fun onConcede() =
        synchronized(sessionLock) {
            sendGameOver(ResultReason.Concede)
        }

    /** Handle SetSettingsReq: submit immutable policy input, then echo the response. */
    override fun onSettings(greMsg: ClientToGREMessage) =
        synchronized(sessionLock) {
            val reqSettings = greMsg.setSettingsReq
            val incoming = reqSettings.settings
            log.info(
                "MatchSession: SetSettingsReq (stops={} transientStops={})",
                incoming.stopsCount,
                incoming.transientStopsCount,
            )

            val settings = gameBridge.priorityPolicy.submit(incoming)

            val (msg, nextMsgId) =
                HandshakeMessages.settingsResp(
                    seatId,
                    counter.currentMsgId(),
                    counter.currentGsId(),
                    settings,
                )
            counter.setMsgId(nextMsgId)
            ProtoDump.dump(msg, "SettingsResp")
            sink.sendRaw(msg)
        }

    // --- Sending helpers ---

    /**
     * Build and send current game state + available actions from the live Forge engine.
     */
    override fun sendRealGameState(
        bridge: GameBridge,
        revealForSeat: Int?,
    ) {
        if (bridge.seat(seatId).action.getPending() == null && !bridge.hasPendingNonActionInteraction()) {
            bridge.awaitActionPriority(seatId)
        }
        if (bridge.seat(seatId).action.getPending() != null) {
            drainCoordinatorFeed()
            return
        }
        drainCoordinatorFeed()
    }

    override fun sendPriorityState(bridge: GameBridge) = drainCoordinatorFeed()

    /** Apply a [BundleBuilder.BundleResult]: tap-log and send. */
    override fun sendBundle(result: BundleBuilder.BundleResult) {
        for (gre in result.messages) {
            if (gre.hasGameStateMessage()) Tap.outboundState(gre.gameStateMessage)
            if (gre.hasActionsAvailableReq()) Tap.outboundActions(gre.actionsAvailableReq)
        }
        sendBundledGRE(result.messages)
    }

    private fun drainCoordinatorFeed() {
        drainCoordinatorBarrier(this, gameBridge, seatId)
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
    override fun sendGameOver(reason: ResultReason) {
        val bridge = gameBridge
        val humanPlayer = bridge.getPlayer(seatId)
        val humanWon = humanPlayer?.getOutcome()?.hasWon() ?: false
        val winningTeam = if (humanWon) 1 else 2
        val losingPlayerSeatId = if (humanWon) 2 else 1
        val losingPlayer = bridge.getPlayer(SeatId(losingPlayerSeatId))
        val lossReason = annotationLossReasonFor(reason, losingPlayer?.getOutcome()?.lossState)

        bridge.cutCoordinator.publishGameOver(
            seatId,
            GameOverIntent(
                winningTeam = winningTeam,
                reason = reason,
                losingPlayerSeatId = losingPlayerSeatId,
                lossReason = lossReason,
            ),
        )
        deliverCommittedCoordinatorBatches(this, bridge, seatId)
        log.info("MatchSession: sent game-over GRE sequence (winner=team{}, reason={})", winningTeam, reason)

        // Send MatchCompleted room state — triggers the client's result screen
        val matchCompletedMsg = HandshakeMessages.matchCompleted(matchId, winningTeam, playerId, reason)
        sink.sendRaw(matchCompletedMsg)
        log.info("MatchSession: sent MatchCompleted room state")

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

    // --- Low-level helpers ---

    /**
     * Send multiple GRE messages bundled in one GreToClientEvent + mirror to peer.
     *
     * Sink-boundary auto-mark: every outgoing prompt-bearing GRE bumps
     * [MessageCounter.lastPromptGsId] before leaving so staleness predicates
     * pick up the new horizon automatically. Direct-builder bypasses
     * (handshake messages, MulliganReq, GroupReq from GsmBuilder) get the
     * same treatment as bundle-built messages — the funnel guarantees it.
     */
    override fun sendBundledGRE(messages: List<GREToClientMessage>) {
        val firstMsgId = messages.firstOrNull()?.msgId
        val maxGsId = messages.maxOfOrNull { it.gameStateId } ?: 0
        val playback = firstMsgId?.let { ctx.bridge.playbackFor(seatId) }
        if (playback != null) {
            for (batch in playback.drainQueueBeforeMsgId(firstMsgId, maxGsId)) {
                sendBundledGREDirect(batch)
            }
        }
        sendBundledGREDirect(messages)
    }

    private fun sendBundledGREDirect(messages: List<GREToClientMessage>) {
        for (m in messages) {
            if (m.hasGameStateMessage()) counter.markGameStateGsId(m.gameStateMessage.gameStateId)
            markIfPrompt(counter, m.type, m.gameStateId, m.msgId)
            if (m.type in PROMPT_GRE_TYPES) {
                lastPrompt = m
                autopush?.onPrompt(m)
            }
        }
        recorder?.recordOutbound(messages)
        sink.send(messages)
        mirrorToFamiliar(messages)
    }

    private fun requestAutoAdvance(reason: String) {
        if (autoAdvanceClosed.get()) return
        autoAdvanceRequested.set(true)
        if (!autoAdvanceRunning.compareAndSet(false, true)) return

        val accepted =
            executeDeferred {
                try {
                    do {
                        autoAdvanceRequested.set(false)
                        synchronized(sessionLock) {
                            if (gameBridge.getGame() == null) return@synchronized
                            log.debug("MatchSession: auto-advance pump ({})", reason)
                            autoPassEngine.autoPassAndAdvance()
                        }
                    } while (autoAdvanceRequested.get())
                } catch (t: Throwable) {
                    log.warn("MatchSession: auto-advance pump failed: {}", t.message, t)
                } finally {
                    autoAdvanceRunning.set(false)
                    if (autoAdvanceRequested.get()) requestAutoAdvance("reschedule")
                }
            }
        if (!accepted) autoAdvanceRunning.set(false)
    }

    private fun requestPlaybackDrain() {
        if (autoAdvanceClosed.get()) return
        executeDeferred {
            try {
                synchronized(sessionLock) {
                    if (gameBridge.getGame() == null) return@synchronized
                    drainCoordinatorFeed()
                }
            } catch (t: Throwable) {
                log.warn("MatchSession: playback drain failed: {}", t.message, t)
            }
        }
    }

    private fun executeDeferred(block: () -> Unit): Boolean {
        pendingDeferredWork.incrementAndGet()
        return try {
            autoAdvanceExecutor.execute {
                try {
                    block()
                } finally {
                    pendingDeferredWork.decrementAndGet()
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            pendingDeferredWork.decrementAndGet()
            false
        }
    }

    /** Wait until deferred auto-advance and playback work caused by prior input has completed. */
    internal fun awaitQuiescence(timeoutMs: Long) {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)

        while (true) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) throw TimeoutException("Match session did not become quiescent within ${timeoutMs}ms")

            try {
                autoAdvanceExecutor.submit {}.get(remainingNanos, TimeUnit.NANOSECONDS)
            } catch (_: RejectedExecutionException) {
                return
            }

            if (pendingDeferredWork.get() == 0 && !autoAdvanceRequested.get() && !autoAdvanceRunning.get()) return
        }
    }

    fun close() {
        autoAdvanceClosed.set(true)
        autoAdvanceRequested.set(false)
        if (gameBridge.autoAdvanceRequester === autoAdvanceRequest) {
            gameBridge.autoAdvanceRequester = null
        }
        if (gameBridge.playbackDrainRequester === playbackDrainRequest) {
            gameBridge.playbackDrainRequester = null
        }
        autoAdvanceExecutor.shutdownNow()
        autopush?.shutdown()
    }

    /** Send a copy of GRE messages to the Familiar (seat 2) via registry. */
    private fun mirrorToFamiliar(messages: List<GREToClientMessage>) {
        if (seatId != gameBridge.seating.humanSeat) return
        val peer = registry.getPeer(matchId, seatId) ?: return
        // Only mirror to FamiliarSession — paired peers build their own state
        // via per-seat GamePlayback.
        if (peer !is FamiliarSession) return
        val mirrorSeat = 2
        // Filter out CastingTimeOptionsReq — Familiar must not auto-respond to modal prompts
        val filtered = messages.filter { it.type != GREMessageType.CastingTimeOptionsReq_695e }
        if (filtered.isEmpty()) return
        val mirrored =
            filtered.map { gre ->
                val builder = gre.toBuilder().clearSystemSeatIds().addSystemSeatIds(mirrorSeat)
                // Strip Private gameObjects not visible to mirror seat (client
                // omits Limbo objects from non-owner messages).
                if (builder.hasGameStateMessage()) {
                    val gsm = builder.gameStateMessage.toBuilder()
                    val filtered =
                        gsm.gameObjectsList.filter { obj ->
                            obj.visibility != Visibility.Private || obj.viewersList.contains(mirrorSeat)
                        }
                    gsm.clearGameObjects().addAllGameObjects(filtered)
                    builder.setGameStateMessage(gsm.build())
                }
                builder.build()
            }
        peer.sink.send(mirrored)
    }

    /** Pacing delay — skipped when paceDelayMs == 0 (tests). */
    override fun paceDelay(multiplier: Int) {
        val delay = paceDelayMs * multiplier
        if (delay > 0) Thread.sleep(delay)
    }
}

internal fun annotationLossReasonFor(
    resultReason: ResultReason,
    lossState: GameLossReason?,
): AnnotationLossReason =
    if (resultReason == ResultReason.Concede) {
        AnnotationLossReason.Concede
    } else {
        when (lossState) {
            GameLossReason.LifeReachedZero -> AnnotationLossReason.LifeTotal
            GameLossReason.Poisoned -> AnnotationLossReason.Poison
            GameLossReason.Milled -> AnnotationLossReason.DrawFromEmptyLibrary
            GameLossReason.Conceded -> AnnotationLossReason.Concede
            GameLossReason.CommanderDamage,
            GameLossReason.IntentionalDraw,
            GameLossReason.OpponentWon,
            GameLossReason.SpellEffect,
            null,
            -> AnnotationLossReason.LifeTotal
        }
    }
