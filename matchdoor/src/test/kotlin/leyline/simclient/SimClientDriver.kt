package leyline.simclient

import leyline.testkit.MatchFlowHarness
import leyline.testkit.performAction
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
    val durationMs: Long = 0,
    val aiConsulted: Int = 0,
    val aiChose: Int = 0,
    val aiConsultedByPrompt: Map<String, Int> = emptyMap(),
    val aiChoseByPrompt: Map<String, Int> = emptyMap(),
    val warnsByLogger: Map<String, Int> = emptyMap(),
    val errorsByType: Map<String, Int> = emptyMap(),
)

class SimClientDriver(
    val harness: MatchFlowHarness,
    private val log: PlayerLogWriter,
    private val maxTurns: Int = 50,
    private val maxIterations: Int = 2_000,
    private val connect: () -> Unit = { harness.connectAndKeep() },
    /**
     * When non-null, the driver consults this advisor for `ActionsAvailableReq`
     * decisions before falling back to greedy heuristics. Iteration 1 covers
     * priority-window action picking only; other prompt types stay greedy.
     */
    private val forgeAi: ForgeAiPolicy? = null,
) {
    private val logger = LoggerFactory.getLogger(SimClientDriver::class.java)
    private var lastFlushedSize = 0

    private companion object {
        const val TURN_STALL_THRESHOLD = 200

        /**
         * Cap for greedy NumericInput responses. ChooseX prompts use
         * `req.maxValue = Int.MAX_VALUE`; submitting that value makes the
         * cost engine try to pay 2³¹−1 mana and refuse the cast. A small
         * positive value (paying X = 3) exercises the X-cost emission paths
         * without burning real games on un-payable casts.
         */
        const val NUMERIC_INPUT_DEFAULT_MAX = 3
    }

    /** Per-prompt-type counts of how often the AI advisor was consulted vs. yielded a usable choice. */
    private val aiConsultedByPrompt = mutableMapOf<String, Int>()
    private val aiChoseByPrompt = mutableMapOf<String, Int>()

    private fun bumpConsulted(prompt: String) {
        aiConsultedByPrompt.merge(prompt, 1) { a, b -> a + b }
    }

    private fun bumpChose(prompt: String) {
        aiChoseByPrompt.merge(prompt, 1) { a, b -> a + b }
    }

    fun runOneGame(): GameStats {
        val tap = GameLogCapture().apply { start() }
        val t0 = System.nanoTime()
        connect()
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
            val acted = takeOneStep()
            flushNewMessagesToLog()
            // Only count "no-progress" iterations when we actually tried to
            // submit something. Race-guard skips (no pending action) leave the
            // engine async — give it room before declaring a stall.
            if (acted && harness.allMessages.size == before) {
                stuckAtPriority++
                if (stuckAtPriority >= 3) {
                    logger.warn("SimClientDriver: no progress after iter $iter, breaking")
                    break
                }
            } else if (acted) {
                stuckAtPriority = 0
            }
            // Detect "turn-stall" — too many iterations on the same turn means
            // engine is grinding combat-phase priority windows or AI is stuck
            // proposing unaffordable casts. Concede to end the game cleanly.
            val cur = currentTurnOrNull() ?: break
            if (cur != lastTurn) {
                lastTurn = cur
                lastTurnIter = iter
            } else if (iter - lastTurnIter > TURN_STALL_THRESHOLD) {
                logger.warn("SimClientDriver: turn $cur stalled for >$TURN_STALL_THRESHOLD iters, conceding")
                concedeAndFlush(reason = "stall", logFailureAtError = true)
                break
            }
        }
        // Final cleanup — if loop exited but game is still active (max-turns,
        // no-progress break, or iter cap), concede so the game produces a proper
        // game-over sequence and gameOver=true in stats.
        if (!harness.isGameOver()) {
            concedeAndFlush(reason = "cleanup", logFailureAtError = false)
        }
        log.flush()
        val histogram =
            harness.allMessages
                .filter { isPrompt(it) }
                .groupingBy { it.type }
                .eachCount()
        val durationMs = (System.nanoTime() - t0) / 1_000_000
        val (warns, errors) = tap.stopAndDrain()
        return GameStats(
            turn = currentTurnOrNull() ?: lastTurn,
            gameOver = harness.isGameOver(),
            iterations = iter,
            totalMessages = harness.allMessages.size,
            promptHistogram = histogram,
            hitIterCap = hitIterCap,
            durationMs = durationMs,
            aiConsulted = aiConsultedByPrompt.values.sum(),
            aiChose = aiChoseByPrompt.values.sum(),
            aiConsultedByPrompt = aiConsultedByPrompt.toMap(),
            aiChoseByPrompt = aiChoseByPrompt.toMap(),
            warnsByLogger = warns,
            errorsByType = errors,
        )
    }

    private fun concedeAndFlush(
        reason: String,
        logFailureAtError: Boolean,
    ) {
        try {
            harness.session.onConcede()
            // Concede emits via sink directly; drain pulls those bytes into
            // allMessages so the log writer sees them.
            harness.drainSink()
            flushNewMessagesToLog()
        } catch (t: Throwable) {
            val msg = "SimClientDriver: $reason concede failed: ${t::class.simpleName}: ${t.message}"
            if (logFailureAtError) logger.error(msg, t) else logger.warn(msg)
        }
    }

    /**
     * Returns true if a real action / response was submitted; false if we
     * skipped due to no pending action (engine is async and not yet ready —
     * the caller should NOT count this toward the no-progress safety).
     */
    private fun takeOneStep(): Boolean {
        // Race guard: if no action is currently pending, the engine has already
        // auto-passed past whatever priority window produced our last observed
        // prompt. Submitting now triggers
        // `WARN ActionPerformer: PerformActionResp but no pending action`.
        // Drain instead so the auto-pass loop's outbound messages flush and we
        // pick up the next real prompt on the next iteration.
        if (!harness.hasPendingAction()) {
            harness.drainSink()
            return false
        }
        val (msg, type) =
            lastPromptMessage() ?: run {
                harness.passPriority()
                return true
            }
        when (type) {
            GREMessageType.DeclareAttackersReq_695e -> {
                // Two-phase: iterative auto-declare, then submit. Without the
                // submit step the engine re-prompts the same DeclareAttackersReq
                // forever.
                harness.declareAllAttackers()
                harness.submitAttackers()
            }
            GREMessageType.DeclareBlockersReq_695e -> {
                if (!consultForgeAiForBlockers()) harness.declareNoBlockers()
            }
            GREMessageType.ActionsAvailableReq_695e -> {
                // Race mitigation: skip the AI consultation entirely when the AAR
                // exposes nothing castable. Forge AI's search adds tens of ms;
                // during that window leyline's auto-pass loop frequently consumes
                // the priority window, and our subsequent `passPriority()` lands
                // when nothing is pending → ActionPerformer "no pending action"
                // warn + spurious resync. Pass-only AARs need no thinking.
                if (hasCastableActionsInAar() && consultForgeAiForAar()) return true
                // Greedy fallback (or AI declined / no castable). Order: play
                // land, cast any spell from the AAR's active list, pass.
                if (canPlayLand()) {
                    harness.playLand()
                    return true
                }
                if (castFirstAvailableSpell()) return true
                harness.passPriority()
            }
            GREMessageType.SelectTargetsReq_695e -> respondSelectTargets(msg)
            GREMessageType.GroupReq_695e -> respondGroup(msg)
            GREMessageType.CastingTimeOptionsReq_695e -> respondCastingTimeOptions()
            GREMessageType.NumericInputReq_695e -> respondNumericInput()
            GREMessageType.IntermissionReq_695e -> harness.passPriority()
            // OptionalActionMessage is handled by [MatchFlowHarness.drainSink]'s
            // autoRespondToOptionalAction (defaults to AllowYes). No driver-side
            // response needed — and re-responding here would warn-and-noop in
            // [OptionalActionHandler] since pendingOptionalAction is already null.
            else -> harness.passPriority()
        }
        return true
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
     * Greedy CastingTimeOptionsReq response: accept the first non-zero option
     * when present (ctoId > 0 = a specific cost like Kicker / Bargain / etc.);
     * otherwise decline (ctoId = 0). Picking the first non-zero option exercises
     * the kicker / Bargain / Offspring acceptance path that pure-decline never
     * reaches, surfacing the downstream prompts those costs trigger (NumericInput
     * for {X}-style kickers, additional CTO rounds for stacked options).
     *
     * If a modal-required prompt rejects our choice, fall through to passPriority
     * so the iter cap fires deterministically rather than blocking.
     */
    private fun respondCastingTimeOptions() {
        val msg = harness.allMessages.lastOrNull { it.hasCastingTimeOptionsReq() }
        val ctoId =
            msg
                ?.castingTimeOptionsReq
                ?.castingTimeOptionReqList
                ?.firstOrNull { it.ctoId != 0 }
                ?.ctoId
                ?: 0
        runCatching { harness.respondToOptionalCost(ctoId) }
            .onFailure {
                logger.warn(
                    "respondCastingTimeOptions: ctoId={} failed ({}), falling back to passPriority",
                    ctoId,
                    it::class.simpleName,
                )
                harness.passPriority()
            }
    }

    /**
     * NumericInputReq response: pick a small positive value rather than 0.
     * Most NumericInput prompts in practice are X-cost choices (ChooseX) —
     * submitting 0 makes the spell do nothing, which is greedy-cheap but
     * useless for exercising X-cost emission paths.
     *
     * `req.maxValue` for ChooseX is `Int.MAX_VALUE` (see
     * `NumericInputHandler.sendNumericInputReq`), so we cap at a small
     * realistic value the cost engine can actually pay. The lower bound from
     * `req.minValue` wins when set (defensive — engine sends min=0 by default).
     */
    private fun respondNumericInput() {
        val req = harness.allMessages.lastOrNull { it.hasNumericInputReq() }?.numericInputReq
        val choice =
            if (req == null) {
                0
            } else {
                req.minValue.coerceAtLeast(NUMERIC_INPUT_DEFAULT_MAX.coerceAtMost(req.maxValue))
            }
        harness.respondToNumericInput(choice)
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
     * Consult the Forge-AI advisor for a priority-window decision. Returns
     * true if a choice was submitted (engine will advance), false if no AI was
     * configured or the AI declined / produced no actionable match.
     */
    private fun consultForgeAiForAar(): Boolean {
        val ai = forgeAi ?: return false
        bumpConsulted("ActionsAvailableReq")
        val choice = ai.chooseAarAction() ?: return false
        bumpChose("ActionsAvailableReq")
        val msg =
            performAction {
                actionType = choice.actionType
                instanceId = choice.instanceId
                grpId = choice.grpId
            }
        harness.session.onPerformAction(harness.submitWithGsId(msg))
        harness.drainSink()
        return true
    }

    /**
     * Consult the Forge-AI advisor for a `DeclareBlockersReq` decision. Returns
     * true when blocks were submitted, false when no AI / no blocks chosen
     * (caller falls back to `declareNoBlockers`).
     */
    private fun consultForgeAiForBlockers(): Boolean {
        val ai = forgeAi ?: return false
        bumpConsulted("DeclareBlockersReq")
        val assignments = ai.chooseBlockers() ?: return false
        bumpChose("DeclareBlockersReq")
        harness.declareBlockers(assignments)
        return true
    }

    /**
     * Cheap predicate — does the current AAR offer any non-Pass action? Used
     * to skip the AI advisor on cleanup-step priority windows where the engine
     * just wants Pass and AI search would only burn CPU + invite races.
     */
    private fun hasCastableActionsInAar(): Boolean {
        val actions = harness.accumulator.actions ?: return false
        return actions.actionsList.any {
            it.actionType == ActionType.Cast ||
                it.actionType == ActionType.Play_add3 ||
                it.actionType == ActionType.Activate_add3
        }
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
        // Route through the harness wrapper so the cast carries a
        // realistic gsId (matches real-client reflection). Direct
        // session.onPerformAction would short-circuit the staleness
        // predicate via `clientGsId != 0` and reduce simclient's
        // coverage of the production path.
        harness.session.onPerformAction(harness.submitWithGsId(msg))
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
            msg.hasCastingTimeOptionsReq() ||
            msg.hasNumericInputReq()

    private fun flushNewMessagesToLog() {
        val total = harness.allMessages.size
        if (total <= lastFlushedSize) return
        val fresh = harness.allMessages.subList(lastFlushedSize, total).toList()
        log.writeBundle(fresh)
        lastFlushedSize = total
    }
}
