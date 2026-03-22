package leyline.match

import forge.game.Game
import forge.game.phase.PhaseType
import leyline.bridge.AutoPassReason
import leyline.bridge.ClientAutoPassState
import leyline.bridge.PlayerAction
import leyline.bridge.PriorityDecision
import leyline.bridge.SeatId
import leyline.game.BundleBuilder
import leyline.game.GameBridge
import org.slf4j.LoggerFactory

/**
 * Auto-pass loop: advances the engine through phases where the player has no
 * meaningful actions, drains AI-action playback diffs, and delegates to
 * [CombatHandler] / [TargetingHandler] when interactive prompts arise.
 *
 * Extracted from [MatchSession] for independent testability.
 * Uses [SessionOps] for message sending and tracing. Protocol sequencing
 * uses the shared [MessageCounter][leyline.game.MessageCounter] via
 * `ops.counter` — no seeding or syncing needed.
 */
class AutoPassEngine(
    private val ops: SessionOps,
    private val combatHandler: CombatHandler,
    private val targetingHandler: TargetingHandler,
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
    fun autoPassAndAdvance(bridge: GameBridge) {
        repeat(MAX_ITERATIONS) {
            val game = bridge.getGame() ?: return
            if (game.isGameOver) {
                ops.traceEvent(MatchEventType.GAME_OVER, game, "game over detected")
                ops.sendGameOver()
                return
            }

            // Drain pending AI-action diffs
            if (drainPlayback(bridge)) return@repeat

            val human = bridge.getPlayer(SeatId(ops.seatId))
            val phase = game.phaseHandler.phase
            val isHumanTurn = human != null && game.phaseHandler.playerTurn == human
            val isAiTurn = human != null && !isHumanTurn

            // Combat phase handling
            when (combatHandler.checkCombatPhase(bridge, game, phase, isHumanTurn, isAiTurn)) {
                CombatHandler.Signal.STOP -> return
                CombatHandler.Signal.SEND_STATE -> {
                    // AI turn: never offer actions — real server sends combat GSMs
                    // with actionsCount=0. Sending Cast actions during AI combat
                    // makes the client stuck (no Pass button), causing 120s timeout.
                    // Pacing already ran inside checkCombatPhase; playback drain
                    // provides the visual state update.
                    if (isAiTurn) {
                        log.debug("SEND_STATE downgraded: AI turn at {}, skipping action offer", phase)
                        // fall through to action check / auto-pass
                    } else {
                        // Human turn: only send state if human has meaningful actions.
                        // SEND_STATE bypasses checkHumanActions, so without this guard
                        // the client can get stuck showing "My Turn" with only Pass.
                        val bb = ops.bundleBuilder!!
                        val actions = bb.buildActions(game)
                        if (!BundleBuilder.shouldAutoPass(actions)) {
                            ops.sendRealGameState(bridge)
                            return
                        }
                        log.debug("SEND_STATE downgraded: only pass actions at {}", phase)
                        // fall through to action check / auto-pass
                    }
                }
                CombatHandler.Signal.CONTINUE -> {} // fall through to action check
            }

            // Interactive prompt (targeting, sacrifice, discard, etc.)
            when (targetingHandler.checkPendingPrompt(bridge, game)) {
                TargetingHandler.PromptResult.SENT_TO_CLIENT -> return
                TargetingHandler.PromptResult.AUTO_RESOLVED -> return@repeat // re-evaluate
                TargetingHandler.PromptResult.NONE -> {} // continue
            }

            // Action check — prompt human if meaningful actions exist
            val decision = checkHumanActions(game, isAiTurn)
            if (decision is PriorityDecision.Grant) {
                ops.sendRealGameState(bridge)
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
        val human2 = game2?.let { bridge.getPlayer(SeatId(ops.seatId)) }
        val stillAiTurn = human2 != null && game2.phaseHandler.playerTurn != human2
        if (stillAiTurn) {
            log.debug("max-iterations: AI turn, suppressing ActionsAvailableReq")
        } else {
            ops.sendRealGameState(bridge)
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
        val playback = bridge.playbacks[SeatId(ops.seatId)] ?: return false
        if (!playback.hasPendingMessages()) return false
        val batches = playback.drainQueue()
        for ((idx, batch) in batches.withIndex()) {
            if (idx > 0) ops.paceDelay(1)
            ops.sendBundledGRE(batch) // sendBundledGRE records client-seen turn info
        }
        log.debug("drainPlayback: drained {} batches", batches.size)
        // Do NOT snapshot current engine state here — the playback diffs represent
        // an earlier point in time. Snapshotting now would advance the diff baseline
        // past phases the client never saw (e.g. Draw phase skipped by PhaseStopProfile),
        // causing subsequent diffs to omit new objects (drawn cards) that the client
        // hasn't received yet. The next buildDiffFromGame() call will snapshot correctly.
        return true
    }

    /**
     * Check if human has meaningful actions. Returns [PriorityDecision.Grant]
     * if state should be sent, [PriorityDecision.Skip] otherwise.
     */
    private fun checkHumanActions(game: Game, isAiTurn: Boolean): PriorityDecision {
        if (isAiTurn) {
            return PriorityDecision.Skip(AutoPassReason.OnlyPassActions)
        }
        val actions = ops.bundleBuilder!!.buildActions(game)

        // Full control: always grant priority (never auto-pass on session side)
        if (autoPassState.isFullControl) {
            val decision = PriorityDecision.Grant(
                phase = game.phaseHandler.phase?.name ?: "UNKNOWN",
                actionCount = actions.actionsCount,
            )
            recordDecision(game, decision)
            ops.traceEvent(MatchEventType.SEND_STATE, game, "fullControl: grant")
            return decision
        }

        // Client autoPassOption active + no stop-worthy actions → skip
        if (autoPassState.shouldAutoPass() && BundleBuilder.shouldAutoPass(actions)) {
            val decision = PriorityDecision.Skip(AutoPassReason.ClientAutoPass)
            recordDecision(game, decision)
            ops.traceEvent(MatchEventType.AUTO_PASS, game, "clientAutoPass: ${autoPassState.autoPassOption}")
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
        ops.traceEvent(MatchEventType.SEND_STATE, game, "actions: $actionSummary")
        return decision
    }

    /**
     * Submit auto-pass or wait for AI/engine. Returns [LoopSignal.CONTINUE] to
     * keep iterating, or [LoopSignal.EXIT] when the caller should return
     * (priority granted to client, game over, or timeout).
     */
    private fun advanceOrWait(bridge: GameBridge, game: Game, phase: PhaseType?, isAiTurn: Boolean): LoopSignal {
        val pending = bridge.seat(ops.seatId).action.getPending()
        log.debug("autoPass: phase={} turn={} aiTurn={} pending={}", phase, game.phaseHandler.turn, isAiTurn, pending != null)

        if (pending != null) {
            // Opponent-turn phase stops: only stop if the client explicitly
            // toggled this phase via SetSettingsReq with Opponents scope.
            // Engine-internal AI_DEFAULTS in PhaseStopProfile are NOT checked
            // here — they're for the AI's own combat logic.
            if (isAiTurn && phase != null && autoPassState.hasOpponentStop(phase)) {
                ops.traceEvent(MatchEventType.SEND_STATE, game, "opponentStop: ${phase.name}")
                ops.sendRealGameState(bridge)
                return LoopSignal.EXIT // client will respond via onPerformAction
            }

            ops.traceEvent(MatchEventType.AUTO_PASS, game, "human priority, pass-only")
            // During AI turn, skip sending EdictalMessage — real server never
            // sends edictal passes during AI turn. Sending them interrupts the
            // client's animation pipeline (enters post-pass "waiting" state).
            if (!isAiTurn) {
                val edictal = ops.bundleBuilder!!.edictalPass(ops.counter)
                ops.sendBundledGRE(edictal.messages)
            }
            bridge.seat(ops.seatId).action.submitAction(pending.actionId, PlayerAction.PassPriority)
            bridge.awaitPriority()
        } else if (isAiTurn) {
            ops.traceEvent(MatchEventType.AI_TURN_WAIT, game, "waiting for AI")
            val reachedPriority = bridge.awaitPriorityWithTimeout(GameBridge.AI_TURN_WAIT_MS)
            if (!reachedPriority) {
                val g = bridge.getGame()
                if (g != null && g.isGameOver) {
                    ops.traceEvent(MatchEventType.GAME_OVER, game, "game over during AI wait")
                    ops.sendGameOver()
                    return LoopSignal.EXIT
                }
                ops.traceEvent(MatchEventType.AI_TURN_TIMEOUT, game, "AI turn timed out")
                log.warn("autoPass: AI turn timed out, suppressing ActionsAvailableReq")
                return LoopSignal.EXIT
            }
        } else {
            ops.traceEvent(MatchEventType.PRIORITY_GRANT, game, "waiting for engine")
            log.warn("autoPass: no pending action, waiting for priority")
            bridge.awaitPriority()
        }
        return LoopSignal.CONTINUE
    }
}
