package leyline.match

import forge.game.Game
import forge.game.phase.PhaseType
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.AutoPassReason
import leyline.bridge.types.ClientAutoPassState
import leyline.bridge.types.PriorityDecision
import leyline.game.bundle.BundleBuilder
import leyline.game.state.GameBridge
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
    private val tracer: SessionTracer,
    private val bundles: BundleBuilderHolder,
    private val pacing: Pacing,
    private val combatHandler: CombatHandler,
    private val targetingHandler: TargetingHandler,
    private val optionalActionHandler: OptionalActionHandler,
    private val autoPassState: ClientAutoPassState = ClientAutoPassState(),
) {
    private val log = LoggerFactory.getLogger(AutoPassEngine::class.java)

    /** Control flow signal from [advanceOrWait]. */
    private enum class LoopSignal { CONTINUE, EXIT }

    companion object {
        private const val MAX_ITERATIONS = 50
        private const val MAX_DECISIONS = 200
    }

    /** Recent priority decisions for debug API. */
    private val recentDecisions = ArrayDeque<PriorityDecisionEntry>()

    data class PriorityDecisionEntry(
        val ts: Long,
        val phase: String?,
        val turn: Int,
        val decision: PriorityDecision,
    )

    /** Snapshot of recent decisions for the debug API. */
    fun decisionLog(): List<PriorityDecisionEntry> = synchronized(recentDecisions) {
        recentDecisions.toList()
    }

    /** Clear decision history for puzzle hot-swap. */
    fun reset() {
        synchronized(recentDecisions) { recentDecisions.clear() }
    }

    private fun recordDecision(game: Game, decision: PriorityDecision) {
        val entry = PriorityDecisionEntry(
            ts = System.currentTimeMillis(),
            phase = game.phaseHandler.phase?.name,
            turn = game.phaseHandler.turn,
            decision = decision,
        )
        synchronized(recentDecisions) {
            recentDecisions.addLast(entry)
            while (recentDecisions.size > MAX_DECISIONS) recentDecisions.removeFirst()
        }
    }

    /**
     * Auto-pass through phases where the player has no meaningful actions.
     * Detects combat phases and sends appropriate combat prompts.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // linear check-and-return pipeline; splitting obscures flow
    fun autoPassAndAdvance(bridge: GameBridge) {
        repeat(MAX_ITERATIONS) {
            val game = bridge.getGame() ?: return
            if (game.isGameOver) {
                tracer.traceEvent(MatchEventType.GAME_OVER, game, "game over detected")
                sink.sendGameOver()
                return
            }

            // Drain pending AI-action diffs
            if (drainPlayback(bridge)) return@repeat

            val human = bridge.getPlayer(counters.seatId)
            val phase = game.phaseHandler.phase
            val isHumanTurn = human != null && game.phaseHandler.playerTurn == human
            val isAiTurn = human != null && !isHumanTurn

            // Damage assignment prompt (dedicated future, not action bridge).
            // Must run before combat phase SEND_STATE handling: COMBAT_DAMAGE on the
            // human turn emits a visual checkpoint, but manual assignment takes
            // precedence and should surface AssignDamageReq immediately.
            if (combatHandler.checkPendingDamageAssignment(bridge)) return

            // Combat phase handling
            when (combatHandler.checkCombatPhase(bridge, game, phase, isHumanTurn, isAiTurn)) {
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
                        val bb = bundles.bundleBuilder!!
                        val actions = bb.buildActions()
                        if (!BundleBuilder.shouldAutoPass(actions)) {
                            sink.sendRealGameState(bridge)
                            return
                        }
                        log.debug("SEND_STATE: emitting state-only diff at {}", phase)
                        sink.sendBundle(bb.stateOnlyDiff(game, counters.counter))
                        // State-only diffs carry no actions — the client cannot respond.
                        // If the engine is blocked at chooseSpellAbilityToPlay with a
                        // pending pass-only action, fall through to advanceOrWait so it
                        // auto-passes via edictalPass + submitAction. Without this the
                        // engine hangs until bridgeTimeoutMs.
                        if (bridge.seat(counters.seatId.value).action.getPending() == null) {
                            return
                        }
                    }
                }
                CombatHandler.Signal.CONTINUE -> {} // fall through to action check
            }

            // Optional action prompt — "you may" trigger (dedicated future)
            if (optionalActionHandler.checkPendingOptionalAction(bridge)) return

            // Interactive prompt (targeting, sacrifice, discard, etc.)
            when (targetingHandler.checkPendingPrompt(bridge, game)) {
                TargetingHandler.PromptResult.SENT_TO_CLIENT -> return
                TargetingHandler.PromptResult.AUTO_RESOLVED -> return@repeat // re-evaluate
                TargetingHandler.PromptResult.NONE -> {} // continue
            }

            // Action check — prompt human if meaningful actions exist
            val decision = checkHumanActions(game, isAiTurn)
            if (decision is PriorityDecision.Grant) {
                if (drainPlayback(bridge)) return@repeat
                sink.sendRealGameState(bridge)
                return
            }

            // Auto-pass or wait
            when (advanceOrWait(bridge, game, phase, isAiTurn)) {
                LoopSignal.EXIT -> return
                LoopSignal.CONTINUE -> {} // next iteration
            }
        }

        val game2 = bridge.getGame()
        val phase2 = game2?.phaseHandler?.phase?.name ?: "?"
        val turn2 = game2?.phaseHandler?.turn ?: -1
        log.warn("autoPassAndAdvance: hit max iterations ({}) at phase={} turn={}", MAX_ITERATIONS, phase2, turn2)
        val human2 = game2?.let { bridge.getPlayer(counters.seatId) }
        val stillAiTurn = human2 != null && game2.phaseHandler.playerTurn != human2
        if (stillAiTurn) {
            log.debug("max-iterations: AI turn, suppressing ActionsAvailableReq")
        } else {
            sink.sendRealGameState(bridge)
        }
    }

    /**
     * Drain pending AI-action playback diffs. Returns true if diffs were sent
     * (caller should re-evaluate in next iteration), false if nothing pending.
     *
     * With the shared [MessageCounter], no counter syncing is needed — messages
     * produced by [GamePlayback] already have correct sequence numbers.
     */
    private fun drainPlayback(bridge: GameBridge): Boolean {
        val playback = bridge.playbacks[counters.seatId] ?: return false
        if (!playback.hasPendingMessages()) return false
        val batches = playback.drainQueue()
        for ((idx, batch) in batches.withIndex()) {
            if (idx > 0) pacing.paceDelay(1)
            sink.sendBundledGRE(batch) // sendBundledGRE records client-seen turn info
        }
        log.debug("drainPlayback: drained {} batches", batches.size)
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
    internal fun checkHumanActions(game: Game, isAiTurn: Boolean): PriorityDecision {
        // AI-turn skips bypass the decision log intentionally — they fire every
        // engine step (dozens per AI turn) and would drown out the human-turn
        // decisions that matter for debugging priority/auto-pass issues.
        if (isAiTurn) {
            return PriorityDecision.Skip(AutoPassReason.OnlyPassActions)
        }
        val actions = bundles.bundleBuilder!!.buildActions()

        // Full control: always grant priority (never auto-pass on session side)
        if (autoPassState.isFullControl) {
            val decision = PriorityDecision.Grant(
                phase = game.phaseHandler.phase?.name ?: "UNKNOWN",
                actionCount = actions.actionsCount,
            )
            recordDecision(game, decision)
            tracer.traceEvent(MatchEventType.SEND_STATE, game, "fullControl: grant")
            return decision
        }

        // Client autoPassOption active + no stop-worthy actions → skip
        if (autoPassState.shouldAutoPass() && BundleBuilder.shouldAutoPass(actions)) {
            val decision = PriorityDecision.Skip(AutoPassReason.ClientAutoPass)
            recordDecision(game, decision)
            tracer.traceEvent(MatchEventType.AUTO_PASS, game, "clientAutoPass: ${autoPassState.autoPassOption}")
            return decision
        }

        if (BundleBuilder.shouldAutoPass(actions)) {
            val decision = PriorityDecision.Skip(AutoPassReason.OnlyPassActions)
            recordDecision(game, decision)
            return decision
        }

        val actionSummary = actions.actionsList
            .groupBy { it.actionType.name.removeSuffix("_add3") }
            .map { (t, v) -> "$t=${v.size}" }
            .joinToString(" ")
        val decision = PriorityDecision.Grant(
            phase = game.phaseHandler.phase?.name ?: "UNKNOWN",
            actionCount = actions.actionsCount,
        )
        recordDecision(game, decision)
        tracer.traceEvent(MatchEventType.SEND_STATE, game, "actions: $actionSummary")
        return decision
    }

    /**
     * Submit auto-pass or wait for AI/engine. Returns [LoopSignal.CONTINUE] to
     * keep iterating, or [LoopSignal.EXIT] when the caller should return
     * (priority granted to client, game over, or timeout).
     */
    private fun advanceOrWait(bridge: GameBridge, game: Game, phase: PhaseType?, isAiTurn: Boolean): LoopSignal {
        val pending = bridge.seat(counters.seatId.value).action.getPending()
        log.debug("autoPass: phase={} turn={} aiTurn={} pending={}", phase, game.phaseHandler.turn, isAiTurn, pending != null)

        if (pending != null) {
            // Opponent-turn phase stops: only stop if the client explicitly
            // toggled this phase via SetSettingsReq with Opponents scope.
            // Engine-internal AI_DEFAULTS in PhaseStopProfile are NOT checked
            // here — they're for the AI's own combat logic.
            if (isAiTurn && phase != null && autoPassState.hasOpponentStop(phase)) {
                tracer.traceEvent(MatchEventType.SEND_STATE, game, "opponentStop: ${phase.name}")
                sink.sendRealGameState(bridge)
                return LoopSignal.EXIT // client will respond via onPerformAction
            }

            tracer.traceEvent(MatchEventType.AUTO_PASS, game, "human priority, pass-only")
            // During AI turn, skip sending EdictalMessage — client never
            // sends edictal passes during AI turn. Sending them interrupts the
            // client's animation pipeline (enters post-pass "waiting" state).
            if (!isAiTurn) {
                val edictal = bundles.bundleBuilder!!.edictalPass(counters.counter)
                sink.sendBundledGRE(edictal.messages)
            }
            bridge.seat(counters.seatId.value).action.submitAction(pending.actionId, PlayerAction.PassPriority)
            bridge.awaitPriority()
        } else if (isAiTurn) {
            tracer.traceEvent(MatchEventType.AI_TURN_WAIT, game, "waiting for AI")
            val reachedPriority = bridge.awaitPriorityWithTimeout(bridge.matchConfig.server.aiTurnWaitMs)
            if (!reachedPriority) {
                val g = bridge.getGame()
                if (g != null && g.isGameOver) {
                    tracer.traceEvent(MatchEventType.GAME_OVER, game, "game over during AI wait")
                    sink.sendGameOver()
                    return LoopSignal.EXIT
                }
                tracer.traceEvent(MatchEventType.AI_TURN_TIMEOUT, game, "AI turn timed out")
                log.warn("autoPass: AI turn timed out, suppressing ActionsAvailableReq")
                return LoopSignal.EXIT
            }
        } else {
            tracer.traceEvent(MatchEventType.PRIORITY_GRANT, game, "waiting for engine")
            log.warn("autoPass: no pending action, waiting for priority")
            bridge.awaitPriority()
        }
        return LoopSignal.CONTINUE
    }
}
