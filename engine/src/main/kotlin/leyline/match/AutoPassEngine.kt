package leyline.match

import forge.game.Game
import forge.game.phase.PhaseType
import leyline.bridge.coord.PriorityPolicyRuntime
import leyline.bridge.types.PriorityDecision
import org.slf4j.LoggerFactory

/**
 * Auto-pass loop: advances the engine through phases where the player has no
 * meaningful actions, drains AI-action playback diffs, and delegates to
 * [CombatHandler] / [TargetingHandler] when interactive prompts arise.
 *
 * Protocol sequencing uses the shared [MessageCounter][leyline.game.bundle.MessageCounter]
 * via `counters.counter` — no seeding or syncing needed.
 */
class AutoPassEngine(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val bundles: BundleBuilderHolder,
    private val pacing: Pacing,
    private val combatHandler: CombatHandler,
    private val targetingHandler: TargetingHandler,
    private val optionalActionHandler: OptionalActionHandler,
    private val numericInputHandler: NumericInputHandler,
    private val ctx: SessionContext,
    private val priorityPolicy: PriorityPolicyRuntime = ctx.bridge.priorityPolicy,
) {
    private val log = LoggerFactory.getLogger(AutoPassEngine::class.java)

    /** Control flow signal from [advanceOrWait]. */
    private enum class LoopSignal { CONTINUE, EXIT }

    companion object {
        private const val MAX_ITERATIONS = 50
    }

    /**
     * Auto-pass through phases where the player has no meaningful actions.
     * Detects combat phases and sends appropriate combat prompts.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // linear check-and-return pipeline; splitting obscures flow
    fun autoPassAndAdvance() {
        val bridge = ctx.bridge
        val game = ctx.game
        repeat(MAX_ITERATIONS) {
            // Drain playback before terminal checks. Playback diffs are already
            // allocated on the shared counter; game-over frames must chain after
            // the client-visible queue, not skip over it.
            if (drainPlayback().progressed) return@repeat

            if (game.isGameOver) {
                sink.sendGameOver()
                return
            }

            val human = bridge.getPlayer(counters.seatId)
            val phase = game.phaseHandler.phase
            val isHumanTurn = human != null && game.phaseHandler.playerTurn == human
            val isAiTurn = human != null && !isHumanTurn

            // Damage assignment prompt (dedicated future, not action bridge).
            // Must run before combat phase SEND_STATE handling: COMBAT_DAMAGE on the
            // human turn emits a visual checkpoint, but manual assignment takes
            // precedence and should surface AssignDamageReq immediately.
            if (combatHandler.checkPendingDamageAssignment()) return

            // Combat-time costs and triggered choices can arrive while Forge still
            // reports the current combat step. Surface the pending prompt before
            // offering the same combat declaration prompt again.
            if (optionalActionHandler.checkPendingOptionalAction()) return
            if (numericInputHandler.checkPendingNumericInput()) return
            when (targetingHandler.checkPendingPrompt()) {
                TargetingHandler.PromptResult.SENT_TO_CLIENT -> return
                TargetingHandler.PromptResult.NONE -> {} // continue
            }

            var combatDecision: PriorityDecision? = null
            // Combat phase handling
            when (combatHandler.checkCombatPhase(phase, isHumanTurn, isAiTurn)) {
                CombatHandler.Signal.STOP -> return
                CombatHandler.Signal.SEND_STATE -> {
                    // AI turn: never offer actions — client expects combat GSMs
                    // with actionsCount=0. Sending Cast actions during AI combat
                    // makes the client stuck (no Pass button), causing 120s timeout.
                    // Pacing already ran inside checkCombatPhase; playback drain
                    // provides the visual state update.
                    if (isAiTurn) {
                        log.debug("SEND_STATE downgraded: AI turn at {}, skipping action offer", phase)
                    } else {
                        // Human turn: only send state if human has meaningful actions.
                        // SEND_STATE bypasses checkHumanActions, so without this guard
                        // the client can get stuck showing "My Turn" with only Pass.
                        // Still emit a state-only diff when actions are pass-only so
                        // combat/death animations don't collapse into the next later
                        // priority-stop packet on the human turn.
                        if (drainPlayback().progressed) return@repeat
                        if (bridge.seat(counters.seatId).action.getPending() == null) {
                            log.debug("SEND_STATE: no pending priority window at {}", phase)
                            return
                        }
                        val pending = checkNotNull(bridge.seat(counters.seatId).action.getPending())
                        val hasLegalAction = bridge.cutCoordinator.hasMeaningfulPriorityAction(pending.actionId)
                        combatDecision = priorityPolicy.decideHumanActions(game, hasLegalAction)
                        if (combatDecision is PriorityDecision.Grant) {
                            sink.sendPriorityState(bridge)
                            return
                        }
                        // State-only diffs carry no actions — the client cannot respond.
                        // If the engine is blocked at chooseSpellAbilityToPlay with a
                        // pending pass-only action, fall through to advanceOrWait so it
                        // auto-passes via edictalPass + submitAction. Without this the
                        // engine hangs until bridgeTimeoutMs.
                    }
                }
                CombatHandler.Signal.CONTINUE -> {} // fall through to action check
            }

            // Optional action prompt — "you may" trigger (dedicated future)
            if (optionalActionHandler.checkPendingOptionalAction()) return

            // Numeric input prompt — Cost$ X, Announce$ X, etc. (dedicated future)
            if (numericInputHandler.checkPendingNumericInput()) return

            // Interactive prompt (targeting, sacrifice, discard, etc.)
            when (targetingHandler.checkPendingPrompt()) {
                TargetingHandler.PromptResult.SENT_TO_CLIENT -> return
                TargetingHandler.PromptResult.NONE -> {} // continue
            }

            // Action check — prompt human if meaningful actions exist. On the
            // AI turn, only offer actions after Forge has actually yielded a
            // human priority window; otherwise instant-speed actions can make
            // us emit an ActionsAvailableReq while the AI still has priority.
            if (shouldCheckHumanActions(isAiTurn)) {
                val pending = checkNotNull(bridge.seat(counters.seatId).action.getPending())
                val decision =
                    combatDecision ?: checkHumanActions(
                        game,
                        bridge.cutCoordinator.hasMeaningfulPriorityAction(pending.actionId),
                    )
                if (decision is PriorityDecision.Grant) {
                    if (drainPlayback().progressed) return@repeat
                    sink.sendPriorityState(bridge)
                    return
                }
            }

            // Auto-pass or wait
            when (advanceOrWait(phase, isAiTurn)) {
                LoopSignal.EXIT -> return
                LoopSignal.CONTINUE -> {} // next iteration
            }
        }

        val phase2 = game.phaseHandler.phase?.name ?: "?"
        val turn2 = game.phaseHandler.turn
        log.warn("autoPassAndAdvance: hit max iterations ({}) at phase={} turn={}", MAX_ITERATIONS, phase2, turn2)
        val human2 = bridge.getPlayer(counters.seatId)
        val stillAiTurn = human2 != null && game.phaseHandler.playerTurn != human2
        if (stillAiTurn) {
            log.debug("max-iterations: AI turn, suppressing ActionsAvailableReq")
        } else if (bridge.seat(counters.seatId).action.getPending() != null) {
            sink.sendRealGameState(bridge)
        }
    }

    internal fun shouldCheckHumanActions(
        @Suppress("UNUSED_PARAMETER") isAiTurn: Boolean,
    ): Boolean =
        ctx.bridge
            .seat(counters.seatId)
            .action
            .getPending()
            ?.state
            ?.kind == leyline.bridge.handoff.PendingActionKind.PRIORITY

    /**
     * Drain pending AI-action playback diffs. Returns true if diffs were sent
     * (caller should re-evaluate in next iteration), false if nothing pending.
     *
     * With the shared [MessageCounter], no counter syncing is needed — messages
     * produced by [GamePlayback] already have correct sequence numbers.
     */
    internal fun drainPlayback(): DrainOutcome {
        val playback = ctx.bridge.playbackFor(counters.seatId) ?: return DrainOutcome(sent = false)
        val retainedSynchronization =
            ctx.bridge
                .actionBridge(counters.seatId)
                .getPending()
                ?.state
                ?.kind == leyline.bridge.handoff.PendingActionKind.SYNC_ONLY
        if (!playback.hasPendingMessages() && !retainedSynchronization) return DrainOutcome(sent = false)
        val outcome =
            drainCoordinatorBarrier(
                sink = sink,
                bridge = ctx.bridge,
                seatId = counters.seatId,
                betweenBatches = { pacing.paceDelay(1) },
                beforeDrain = ::suppressPassOnlyAiPriorityPresentation,
            )
        log.debug("drainPlayback: drained committed coordinator feed")
        // Do NOT snapshot current engine state here — the playback diffs represent
        // an earlier point in time. Snapshotting now would advance the diff baseline
        // past phases the client never saw (for example, a draw phase skipped by policy),
        // causing subsequent diffs to omit new objects (drawn cards) that the client
        // hasn't received yet. The next buildDiff() call will advance the cursor correctly.
        return outcome
    }

    private fun suppressPassOnlyAiPriorityPresentation() {
        val bridge = ctx.bridge
        val game = ctx.game
        val human = bridge.getPlayer(counters.seatId) ?: return
        if (game.phaseHandler.playerTurn == human) return
        val pending = bridge.seat(counters.seatId).action.getPending() ?: return
        if (pending.state.kind != leyline.bridge.handoff.PendingActionKind.PRIORITY) return
        val hasLegalAction = bridge.cutCoordinator.hasMeaningfulPriorityAction(pending.actionId)
        if (priorityPolicy.shouldSuppressOpponentPresentation(game, isAiTurn = true, hasLegalAction)) {
            bridge.cutCoordinator.suppressPriorityPresentation(pending.actionId)
        }
    }

    /**
     * Check if human has meaningful actions. Returns [PriorityDecision.Grant]
     * if state should be sent, [PriorityDecision.Skip] otherwise.
     *
     * Internal for testability — tested directly in [AutoPassEngineTest].
     */
    internal fun checkHumanActions(
        game: Game,
        hasLegalAction: Boolean,
    ): PriorityDecision = priorityPolicy.decideHumanActions(game, hasLegalAction)

    /**
     * Submit auto-pass or wait for AI/engine. Returns [LoopSignal.CONTINUE] to
     * keep iterating, or [LoopSignal.EXIT] when the caller should return
     * (priority granted to client, game over, or timeout).
     */
    private fun advanceOrWait(
        phase: PhaseType?,
        isAiTurn: Boolean,
    ): LoopSignal {
        val bridge = ctx.bridge
        val game = ctx.game
        val pending = bridge.seat(counters.seatId).action.getPending()
        log.debug("autoPass: phase={} turn={} aiTurn={} pending={}", phase, game.phaseHandler.turn, isAiTurn, pending != null)

        if (pending != null) {
            // Opponent-turn phase stops: only stop if the client explicitly
            // toggled this phase via SetSettingsReq with Opponents scope.
            // Engine-internal AI defaults are NOT checked here. They are for the
            // AI's own combat logic, not the client's opponent-turn stops.
            if (priorityPolicy.shouldStopForOpponent(isAiTurn, phase)) {
                sink.sendRealGameState(bridge)
                return LoopSignal.EXIT // client will respond via onPerformAction
            }

            // During AI turn, skip sending EdictalMessage — client never
            // sends edictal passes during AI turn. Sending them interrupts the
            // client's animation pipeline (enters post-pass "waiting" state).
            if (!isAiTurn) {
                val edictal = bundles.bundleBuilder.edictalPass(counters.counter)
                sink.sendBundledGRE(edictal.messages)
            }
            bridge.cutCoordinator
                .claimPriorityResponse(
                    pending.actionId,
                    pending.promptGameStateId ?: 0,
                    wotc.mtgo.gre.external.messaging.Messages.Action
                        .newBuilder()
                        .setActionType(wotc.mtgo.gre.external.messaging.Messages.ActionType.Pass)
                        .build(),
                    defer = false,
                )?.let { bridge.cutCoordinator.completeActionClaim(it.actionClaim) }
            bridge.awaitPriority()
        } else if (isAiTurn) {
            val reachedPriority = bridge.awaitPriorityWithTimeout(bridge.engineSettings.aiTurnWaitMs)
            if (!reachedPriority) {
                if (game.isGameOver) {
                    if (drainPlayback().progressed) return LoopSignal.CONTINUE
                    sink.sendGameOver()
                    return LoopSignal.EXIT
                }
                log.warn("autoPass: AI turn timed out, suppressing ActionsAvailableReq")
                return LoopSignal.EXIT
            }
        } else {
            log.warn("autoPass: no pending action, waiting for priority")
            bridge.awaitPriority()
        }
        return LoopSignal.CONTINUE
    }
}
