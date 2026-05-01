package leyline.simclient

import leyline.conformance.MatchFlowHarness
import leyline.conformance.performAction
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Drives one match end-to-end against a [MatchFlowHarness] using a greedy
 * policy, and emits scry-ts-parseable Player.log lines via [PlayerLogWriter].
 *
 * v0 policy:
 *   - mulligan: always keep (delegated to harness.connectAndKeep)
 *   - main phases: play one land per turn, then cast cheapest castable creature, else pass
 *   - declare attackers: send all
 *   - declare blockers: none
 *   - everything else: pass priority
 *
 * Loop runs until the engine reports game-over or [maxTurns] is reached.
 */
data class GameStats(
    val turn: Int,
    val gameOver: Boolean,
    val iterations: Int,
    val totalMessages: Int,
    val promptHistogram: Map<GREMessageType, Int>,
    val hitIterCap: Boolean,
)

class SimClientDriver(
    val harness: MatchFlowHarness,
    private val log: PlayerLogWriter,
    private val maxTurns: Int = 50,
    private val maxIterations: Int = 2_000,
) {
    private val logger = LoggerFactory.getLogger(SimClientDriver::class.java)
    private var lastFlushedSize = 0

    fun runOneGame(): GameStats {
        harness.connectAndKeep()
        flushNewMessagesToLog()

        var iter = 0
        var hitIterCap = false
        var stuckAtPriority = 0
        var lastTurn = currentTurnOrNull() ?: 0
        var lastTurnIter = 0
        while (true) {
            if (harness.isGameOver()) break
            val currentTurn = currentTurnOrNull() ?: break // bridge torn down
            if (currentTurn >= maxTurns) break
            if (iter >= maxIterations) {
                hitIterCap = true
                break
            }
            iter++
            val before = harness.allMessages.size
            takeOneStep()
            flushNewMessagesToLog()
            if (harness.allMessages.size == before) {
                stuckAtPriority++
                if (stuckAtPriority >= 3) {
                    logger.warn("SimClientDriver: no progress after iter $iter, breaking")
                    break
                }
            } else {
                stuckAtPriority = 0
            }
            // Detect "turn-stall" — too many iterations on the same turn means
            // engine is grinding combat-phase priority windows or AI is stuck
            // proposing unaffordable casts. Concede to end the game cleanly.
            val cur = currentTurnOrNull() ?: break
            if (cur != lastTurn) {
                lastTurn = cur
                lastTurnIter = iter
            } else if (iter - lastTurnIter > 200) {
                logger.warn("SimClientDriver: turn $cur stalled for >200 iters, conceding")
                try {
                    harness.session.onConcede()
                    // Concede emits via sink directly; need a drain to pull
                    // those bytes into allMessages so the log writer sees them.
                    harness.drainSink()
                    flushNewMessagesToLog()
                } catch (t: Throwable) {
                    logger.error("SimClientDriver: concede failed: ${t::class.simpleName}: ${t.message}", t)
                }
                break
            }
        }
        // Final cleanup — if loop exited but game is still active (max-turns,
        // no-progress break, or iter cap), concede so the game produces a proper
        // game-over sequence and gameOver=true in stats.
        if (!harness.isGameOver()) {
            try {
                harness.session.onConcede()
                harness.drainSink()
                flushNewMessagesToLog()
            } catch (t: Throwable) {
                logger.warn("SimClientDriver: cleanup concede failed: ${t::class.simpleName}: ${t.message}")
            }
        }
        log.flush()
        val histogram =
            harness.allMessages
                .filter { isPrompt(it) }
                .groupingBy { it.type }
                .eachCount()
        return GameStats(
            turn = currentTurnOrNull() ?: lastTurn,
            gameOver = harness.isGameOver(),
            iterations = iter,
            totalMessages = harness.allMessages.size,
            promptHistogram = histogram,
            hitIterCap = hitIterCap,
        )
    }

    /** Pick one action based on the current pending prompt. */
    private fun takeOneStep() {
        val (msg, type) =
            lastPromptMessage() ?: run {
                harness.passPriority()
                return
            }
        when (type) {
            GREMessageType.DeclareAttackersReq_695e -> {
                // Two-phase: iterative auto-declare, then submit. Without the
                // submit step the engine re-prompts the same DeclareAttackersReq
                // forever.
                harness.declareAllAttackers()
                harness.submitAttackers()
            }
            GREMessageType.DeclareBlockersReq_695e -> harness.declareNoBlockers()
            GREMessageType.ActionsAvailableReq_695e -> {
                // Order: play land, cast any spell from the AAR's active list, pass.
                // Density: cast non-creature spells too (burn, pump, removal). The
                // simclient uses the AAR's Cast actions directly — each carries
                // instanceId+grpId already, so we don't need to walk the hand.
                if (canPlayLand()) {
                    harness.playLand()
                    return
                }
                if (castFirstAvailableSpell()) return
                harness.passPriority()
            }
            GREMessageType.SelectTargetsReq_695e -> respondSelectTargets(msg)
            GREMessageType.GroupReq_695e -> respondGroup(msg)
            GREMessageType.CastingTimeOptionsReq_695e -> respondCastingTimeOptions(msg)
            GREMessageType.IntermissionReq_695e -> harness.passPriority()
            else -> harness.passPriority()
        }
    }

    /** Pick first legal target across all selection slots. */
    private fun respondSelectTargets(msg: GREToClientMessage) {
        val req = msg.selectTargetsReq
        val ids =
            req.targetsList
                .flatMap { sel ->
                    sel.targetsList.take(sel.maxTargets.coerceAtLeast(1)).map { it.targetInstanceId }
                }.filter { it != 0 }
        if (ids.isEmpty()) {
            // Pick fewest legal — engine may accept zero-targets if minTargets==0.
            harness.cancelAction()
            return
        }
        harness.selectTargets(
            ids.distinct().take(
                req.targetsList
                    .firstOrNull()
                    ?.maxTargets
                    ?.coerceAtLeast(1) ?: 1,
            ),
        )
    }

    /**
     * Greedy CastingTimeOptionsReq response: decline all optional costs
     * (ctoId=0). Covers kicker / buyback / Bargain / Offspring decline paths.
     * For modal-required prompts (where the player must pick a mode), this
     * decline can stall the engine — log and fall through to passPriority so
     * the iter cap fires deterministically rather than blocking.
     */
    private fun respondCastingTimeOptions(msg: GREToClientMessage) {
        runCatching { harness.respondToOptionalCost(0) }
            .onFailure {
                logger.warn(
                    "respondCastingTimeOptions: decline failed ({}), falling back to passPriority",
                    it::class.simpleName,
                )
                harness.passPriority()
            }
    }

    /** Greedy GroupReq response: leave order as-is (top stays top, no surveil-to-graveyard). */
    private fun respondGroup(msg: GREToClientMessage) {
        val req = msg.groupReq
        val allIds = req.instanceIdsList.toList()
        // For scry: empty bottom → all stay on top. For surveil: empty graveyard → all back to library.
        harness.respondToScry(bottomInstanceIds = emptyList(), allInstanceIds = allIds)
    }

    private fun canPlayLand(): Boolean {
        val actions = harness.accumulator.actions ?: return false
        return actions.actionsList.any { it.actionType == ActionType.Play_add3 }
    }

    /**
     * Cast the first castable spell from the AAR's active actions list. The Cast
     * actions in the AAR carry instanceId + grpId already — no hand walking needed.
     */
    private fun castFirstAvailableSpell(): Boolean {
        val actions = harness.accumulator.actions ?: return false
        val cast = actions.actionsList.firstOrNull { it.actionType == ActionType.Cast } ?: return false
        val msg =
            performAction {
                actionType = ActionType.Cast
                instanceId = cast.instanceId
                grpId = cast.grpId
            }
        harness.session.onPerformAction(msg)
        harness.drainSink()
        return true
    }

    /** Returns current turn number, or null if the game/bridge has been torn down. */
    private fun currentTurnOrNull(): Int? = runCatching { harness.turn() }.getOrNull()

    private fun lastPromptMessage(): Pair<GREToClientMessage, GREMessageType>? {
        for (i in harness.allMessages.indices.reversed()) {
            val msg = harness.allMessages[i]
            if (isPrompt(msg)) return msg to msg.type
        }
        return null
    }

    private fun isPrompt(msg: GREToClientMessage): Boolean =
        msg.hasActionsAvailableReq() ||
            msg.hasDeclareAttackersReq() ||
            msg.hasDeclareBlockersReq() ||
            msg.hasSelectTargetsReq() ||
            msg.hasGroupReq() ||
            msg.hasSelectNReq() ||
            msg.hasSearchReq() ||
            msg.hasAssignDamageReq() ||
            msg.hasMulliganReq() ||
            msg.hasIntermissionReq() ||
            msg.hasOptionalActionMessage() ||
            msg.hasCastingTimeOptionsReq()

    private fun flushNewMessagesToLog() {
        val total = harness.allMessages.size
        if (total <= lastFlushedSize) return
        val fresh = harness.allMessages.subList(lastFlushedSize, total).toList()
        log.writeBundle(fresh)
        lastFlushedSize = total
    }
}
