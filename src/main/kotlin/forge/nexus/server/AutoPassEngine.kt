package forge.nexus.server

import forge.game.Game
import forge.game.phase.PhaseType
import forge.nexus.debug.GameStateCollector
import forge.nexus.game.BundleBuilder
import forge.nexus.game.GameBridge
import forge.nexus.game.StateMapper
import forge.web.game.PlayerAction
import org.slf4j.LoggerFactory

/**
 * Auto-pass loop: advances the engine through phases where the player has no
 * meaningful actions, drains AI-action playback diffs, and delegates to
 * [CombatHandler] / [TargetingHandler] when interactive prompts arise.
 *
 * Extracted from [MatchSession] for independent testability.
 * Uses [SessionOps] for counter management, message sending, and tracing.
 */
class AutoPassEngine(
    private val ops: SessionOps,
    private val combatHandler: CombatHandler,
    private val targetingHandler: TargetingHandler,
) {
    private val log = LoggerFactory.getLogger(AutoPassEngine::class.java)

    companion object {
        private const val MAX_ITERATIONS = 50
    }

    /**
     * Auto-pass through phases where the player has no meaningful actions.
     * Detects combat phases and sends appropriate combat prompts.
     */
    fun autoPassAndAdvance(bridge: GameBridge) {
        repeat(MAX_ITERATIONS) {
            val game = bridge.getGame() ?: return
            if (game.isGameOver) {
                ops.traceEvent(GameStateCollector.EventType.GAME_OVER, game, "game over detected")
                ops.sendGameOver()
                return
            }

            // Drain pending AI-action diffs
            if (drainPlayback(bridge, game)) return@repeat

            val human = bridge.getPlayer(ops.seatId)
            val phase = game.phaseHandler.phase
            val isHumanTurn = human != null && game.phaseHandler.playerTurn == human
            val isAiTurn = human != null && !isHumanTurn

            // Combat phase handling
            val combatSignal = combatHandler.checkCombatPhase(bridge, game, phase, isHumanTurn, isAiTurn)
            if (combatSignal == CombatHandler.Signal.STOP) return
            if (combatSignal == CombatHandler.Signal.SEND_STATE) {
                ops.sendRealGameState(bridge)
                return
            }

            // Interactive prompt (targeting, sacrifice, discard, etc.)
            when (targetingHandler.checkPendingPrompt(bridge, game)) {
                TargetingHandler.PromptResult.SENT_TO_CLIENT -> return
                TargetingHandler.PromptResult.AUTO_RESOLVED -> return@repeat // re-evaluate
                TargetingHandler.PromptResult.NONE -> {} // continue
            }

            // Action check — prompt human if meaningful actions exist
            if (checkHumanActions(bridge, game, isAiTurn)) {
                ops.sendRealGameState(bridge)
                return
            }

            // Auto-pass or wait
            advanceOrWait(bridge, game, phase, isAiTurn) ?: return
        }

        log.warn("autoPassAndAdvance: hit max iterations ({})", MAX_ITERATIONS)
        ops.sendRealGameState(bridge)
    }

    /**
     * Drain pending AI-action playback diffs. Returns true if diffs were sent
     * (caller should re-evaluate in next iteration), false if nothing pending.
     */
    private fun drainPlayback(bridge: GameBridge, game: Game): Boolean {
        val playback = bridge.playback ?: return false
        if (!playback.hasPendingMessages()) {
            // No queued messages, but the engine thread may have advanced
            // counters (via NexusGamePlayback.captureAndPause) between
            // iterations. Sync so ops.gameStateId stays ahead of the
            // snapshot's gameStateId.
            val (nextMsg, nextGs) = playback.getCounters()
            if (nextMsg > ops.msgIdCounter) {
                log.debug("drainPlayback: sync msgId {} → {}", ops.msgIdCounter, nextMsg)
                ops.msgIdCounter = nextMsg
            }
            if (nextGs > ops.gameStateId) {
                log.debug("drainPlayback: sync gsId {} → {}", ops.gameStateId, nextGs)
                ops.gameStateId = nextGs
            }
            return false
        }
        val batches = playback.drainQueue()
        for ((idx, batch) in batches.withIndex()) {
            if (idx > 0) ops.paceDelay(1)
            ops.sendBundledGRE(batch) // sendBundledGRE updates lastSentTurnInfo
        }
        val (nextMsg, nextGs) = playback.getCounters()
        log.debug("drainPlayback: drained, counters msgId={} gsId={} (was msgId={} gsId={})", nextMsg, nextGs, ops.msgIdCounter, ops.gameStateId)
        ops.msgIdCounter = nextMsg
        ops.gameStateId = nextGs
        // Do NOT snapshot current engine state here — the playback diffs represent
        // an earlier point in time. Snapshotting now would advance the diff baseline
        // past phases the client never saw (e.g. Draw phase skipped by PhaseStopProfile),
        // causing subsequent diffs to omit new objects (drawn cards) that the client
        // hasn't received yet. The next buildDiffFromGame() call will snapshot correctly.
        return true
    }

    /** Check if human has meaningful actions. Returns true if state should be sent. */
    private fun checkHumanActions(bridge: GameBridge, game: Game, isAiTurn: Boolean): Boolean {
        if (isAiTurn) return false
        val actions = BundleBuilder.buildActions(game, ops.seatId, bridge)
        if (BundleBuilder.shouldAutoPass(actions)) return false
        val actionSummary = actions.actionsList
            .groupBy { it.actionType.name.removeSuffix("_add3") }
            .map { (t, v) -> "$t=${v.size}" }
            .joinToString(" ")
        ops.traceEvent(GameStateCollector.EventType.SEND_STATE, game, "actions: $actionSummary")
        return true
    }

    /**
     * Submit auto-pass or wait for AI/engine. Returns Unit on success (loop continues),
     * or null to signal the caller should return (game over / timeout).
     */
    private fun advanceOrWait(bridge: GameBridge, game: Game, phase: PhaseType?, isAiTurn: Boolean): Unit? {
        val pending = bridge.actionBridge.getPending()
        log.debug("autoPass: phase={} turn={} aiTurn={} pending={}", phase, game.phaseHandler.turn, isAiTurn, pending != null)

        if (pending != null) {
            ops.traceEvent(GameStateCollector.EventType.AUTO_PASS, game, "human priority, pass-only")
            // During AI turn, skip sending EdictalMessage — real server never
            // sends edictal passes during AI turn. Sending them interrupts the
            // client's animation pipeline (enters post-pass "waiting" state).
            if (!isAiTurn) {
                val edictal = BundleBuilder.edictalPass(ops.seatId, ops.msgIdCounter, ops.gameStateId)
                ops.msgIdCounter = edictal.nextMsgId
                ops.sendBundledGRE(edictal.messages)
            }
            // Seed BEFORE submit — submitAction unblocks the game thread immediately
            // and it may fire events captured by NexusGamePlayback with stale counters.
            bridge.playback?.seedCounters(ops.msgIdCounter, ops.gameStateId)
            bridge.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
            bridge.awaitPriority()
        } else if (isAiTurn) {
            ops.traceEvent(GameStateCollector.EventType.AI_TURN_WAIT, game, "waiting for AI")
            bridge.playback?.seedCounters(ops.msgIdCounter, ops.gameStateId)
            val reachedPriority = bridge.awaitPriorityWithTimeout(GameBridge.AI_TURN_WAIT_MS)
            if (!reachedPriority) {
                val g = bridge.getGame()
                if (g != null && g.isGameOver) {
                    ops.traceEvent(GameStateCollector.EventType.GAME_OVER, game, "game over during AI wait")
                    ops.sendGameOver()
                    return null
                }
                ops.traceEvent(GameStateCollector.EventType.AI_TURN_TIMEOUT, game, "AI turn timed out")
                log.warn("autoPass: AI turn timed out, sending current state")
                ops.sendRealGameState(bridge)
                return null
            }
        } else {
            ops.traceEvent(GameStateCollector.EventType.PRIORITY_GRANT, game, "waiting for engine")
            log.warn("autoPass: no pending action, waiting for priority")
            bridge.playback?.seedCounters(ops.msgIdCounter, ops.gameStateId)
            bridge.awaitPriority()
        }
        return Unit
    }
}
