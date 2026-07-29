package leyline.match

import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.AutoPassReason
import leyline.bridge.types.ClientAutoPassState
import leyline.bridge.types.PriorityActionFacts
import leyline.bridge.types.PriorityDecision
import org.slf4j.LoggerFactory

/**
 * Auto-pass loop: advances the engine through phases where the player has no
 * meaningful actions, drains AI-action playback diffs, and delegates to
 * [CombatHandler] / [TargetingHandler] when interactive prompts arise.
 *
 * Playback is reduced by the owner before later prompts or terminal output,
 * keeping one protocol sequence.
 */
class AutoPassEngine(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val bundles: BundleBuilderHolder,
    private val combatHandler: CombatHandler,
    private val targetingHandler: TargetingHandler,
    private val optionalActionHandler: OptionalActionHandler,
    private val numericInputHandler: NumericInputHandler,
    private val ctx: SessionContext,
    private val autoPassState: ClientAutoPassState = ClientAutoPassState(),
) {
    private val log = LoggerFactory.getLogger(AutoPassEngine::class.java)

    /** Control flow signal from [advanceOrWait]. */
    private enum class LoopSignal { CONTINUE, EXIT }

    companion object {
        private const val MAX_ITERATIONS = 50
    }

    private fun recordDecision(decision: PriorityDecision) {
        val runtime = ctx.runtime(counters.seatId)
        log.info(
            "event=priority_decision source=session phase={} turn={} decision={}",
            runtime.phase,
            runtime.turn,
            decision,
        )
    }

    /**
     * Auto-pass through phases where the player has no meaningful actions.
     * Detects combat phases and sends appropriate combat prompts.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // linear check-and-return pipeline; splitting obscures flow
    fun autoPassAndAdvance() {
        val bridge = ctx.bridge
        repeat(MAX_ITERATIONS) {
            // Reduce playback before terminal checks so game-over output follows
            // every earlier engine observation on the owner sequence.
            if (drainPlayback()) return@repeat

            val runtime = ctx.runtime(counters.seatId)
            if (runtime.isGameOver) {
                sink.sendGameOver()
                return
            }

            val phase = runtime.phase
            val isHumanTurn = runtime.isPlayerTurn
            val isAiTurn = runtime.isOpponentTurn

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
                TargetingHandler.PromptResult.AUTO_RESOLVED -> return@repeat // re-evaluate
                TargetingHandler.PromptResult.NONE -> {} // continue
            }

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
                        if (drainPlayback()) return@repeat
                        if (bridge.seat(counters.seatId).action.getPending() == null) {
                            log.debug("SEND_STATE: no pending priority window at {}", phase)
                            sink.sendBundle(bundles.bundleBuilder.stateOnlyDiff(ctx.snapshot(), counters.counter))
                            return
                        }
                        val facts = ctx.runtime(counters.seatId).priorityActions
                        if (facts.hasLegalNonManaAction) {
                            sink.sendPriorityState(bridge)
                            return
                        }
                        log.debug("SEND_STATE: emitting state-only diff at {}", phase)
                        sink.sendBundle(bundles.bundleBuilder.stateOnlyDiff(ctx.snapshot(), counters.counter))
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
                TargetingHandler.PromptResult.AUTO_RESOLVED -> return@repeat // re-evaluate
                TargetingHandler.PromptResult.NONE -> {} // continue
            }

            // Action check — prompt human if meaningful actions exist. On the
            // AI turn, only offer actions after Forge has actually yielded a
            // human priority window; otherwise instant-speed actions can make
            // us emit an ActionsAvailableReq while the AI still has priority.
            if (shouldCheckHumanActions()) {
                val facts = runtime.priorityActions
                val decision = checkHumanActions(isAiTurn, facts)
                if (decision is PriorityDecision.Grant) {
                    if (drainPlayback()) return@repeat
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

        val finalRuntime = ctx.runtime(counters.seatId)
        val phase2 = finalRuntime.phase ?: "?"
        val turn2 = finalRuntime.turn
        log.warn("autoPassAndAdvance: hit max iterations ({}) at phase={} turn={}", MAX_ITERATIONS, phase2, turn2)
        val stillAiTurn = finalRuntime.isOpponentTurn
        if (stillAiTurn) {
            log.debug("max-iterations: AI turn, suppressing ActionsAvailableReq")
        } else if (bridge.seat(counters.seatId).action.getPending() != null) {
            sink.sendRealGameState(bridge)
        } else {
            sink.sendBundle(bundles.bundleBuilder.stateOnlyDiff(ctx.snapshot(), counters.counter))
        }
    }

    internal fun shouldCheckHumanActions(): Boolean =
        ctx.bridge
            .seat(counters.seatId)
            .action
            .getPending() != null

    /**
     * Drain pending AI-action playback diffs. Returns true if diffs were sent
     * (caller should re-evaluate in next iteration), false if nothing pending.
     *
     * With the shared [MessageCounter], no counter syncing is needed — messages
     * produced by [GamePlayback] already have correct sequence numbers.
     */
    fun drainPlayback(): Boolean {
        if (!sink.drainPlayback()) return false
        log.debug("drainPlayback: drained owner engine cuts")
        // Do NOT snapshot current engine state here — the playback diffs represent
        // an earlier point in time. Snapshotting now would advance the diff baseline
        // past phases the client never saw (e.g. Draw phase skipped by PhaseStopProfile),
        // causing subsequent diffs to omit new objects (drawn cards) that the client
        // hasn't received yet. The next buildDiff() call will advance the cursor correctly.
        return true
    }

    /**
     * Check if human has meaningful actions. Returns [PriorityDecision.Grant]
     * if state should be sent, [PriorityDecision.Skip] otherwise.
     *
     * Internal for testability — tested directly in [AutoPassEngineTest].
     */
    internal fun checkHumanActions(
        isAiTurn: Boolean,
        facts: PriorityActionFacts? = null,
    ): PriorityDecision {
        val runtime = ctx.runtime(counters.seatId)
        val resolvedFacts = facts ?: runtime.priorityActions
        val hasLegalAction = runtime.hasPlayer && resolvedFacts.hasLegalNonManaAction

        // Full control: always grant priority (never auto-pass on session side)
        if (autoPassState.isFullControl) {
            val decision =
                PriorityDecision.Grant(
                    phase = runtime.phase ?: "UNKNOWN",
                    actionCount = if (hasLegalAction) 1 else 0,
                )
            recordDecision(decision)
            return decision
        }

        // Opponent-turn windows still build actions: legal instants and instant-speed
        // activations must stop, while pass-only windows keep auto-advancing.
        // Client autoPassOption active + no stop-worthy actions → skip.
        if (autoPassState.shouldAutoPass() && !hasLegalAction) {
            val decision = PriorityDecision.Skip(AutoPassReason.ClientAutoPass)
            if (!isAiTurn) {
                recordDecision(decision)
            }
            return decision
        }

        if (!hasLegalAction) {
            val decision = PriorityDecision.Skip(AutoPassReason.OnlyPassActions)
            if (!isAiTurn) recordDecision(decision)
            return decision
        }

        val decision =
            PriorityDecision.Grant(
                phase = runtime.phase ?: "UNKNOWN",
                actionCount = 1,
            )
        recordDecision(decision)
        return decision
    }

    /**
     * Submit auto-pass or wait for AI/engine. Returns [LoopSignal.CONTINUE] to
     * keep iterating, or [LoopSignal.EXIT] when the caller should return
     * (priority granted to client, game over, or timeout).
     */
    private fun advanceOrWait(
        phase: String?,
        isAiTurn: Boolean,
    ): LoopSignal {
        val bridge = ctx.bridge
        val runtime = ctx.runtime(counters.seatId)
        val pending = bridge.seat(counters.seatId).action.getPending()
        log.debug("autoPass: phase={} turn={} aiTurn={} pending={}", phase, runtime.turn, isAiTurn, pending != null)

        if (pending != null) {
            // Opponent-turn phase stops: only stop if the client explicitly
            // toggled this phase via SetSettingsReq with Opponents scope.
            // Engine-internal AI_DEFAULTS in PhaseStopProfile are NOT checked
            // here — they're for the AI's own combat logic.
            if (isAiTurn && phase != null && autoPassState.hasOpponentStop(phase)) {
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
            bridge.seat(counters.seatId).action.submitAction(pending.actionId, PlayerAction.PassPriority)
            ctx.engine.awaitPriority()
        } else if (isAiTurn) {
            val reachedPriority = ctx.engine.awaitPriorityWithTimeout(bridge.matchConfig.server.aiTurnWaitMs)
            if (!reachedPriority) {
                if (ctx.runtime(counters.seatId).isGameOver) {
                    if (drainPlayback()) return LoopSignal.CONTINUE
                    sink.sendGameOver()
                    return LoopSignal.EXIT
                }
                log.warn("autoPass: AI turn timed out, suppressing ActionsAvailableReq")
                return LoopSignal.EXIT
            }
        } else {
            log.warn("autoPass: no pending action, waiting for priority")
            ctx.engine.awaitPriority()
        }
        return LoopSignal.CONTINUE
    }
}
