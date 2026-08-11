package leyline.copilot

import wotc.mtgo.gre.external.messaging.Messages.SelectAction
import wotc.mtgo.gre.external.messaging.Messages.SelectTargetsReq

/**
 * One step of iterative target declaration — the targeting sibling of
 * [CombatDeclarationDiff]. `SelectTargetsReq` is iterative-delta: each
 * `SelectTargetsResp` is a tap, the engine echoes a fresh `SelectTargetsReq`
 * reflecting the accumulated selection, and `SubmitTargetsReq` finalizes.
 *
 * The committed state is read from the prompt (stateless): a target already
 * picked echoes with `legalAction = Unselect` (tapping it would remove it),
 * an available one with `Select`. Compare committed against the AI's desired
 * set and emit the next message — select the missing, unselect the extra,
 * else Submit.
 */
internal object TargetSelectionDiff {
    /**
     * Total targets the prompt requires (summed across groups). Used to decide
     * whether an already-committed selection can be submitted as-is when the AI
     * can no longer re-pick from a fully-committed re-prompt.
     */
    fun requiredRange(req: SelectTargetsReq): IntRange {
        val min = req.targetsList.sumOf { it.minTargets.coerceAtLeast(0) }
        val max =
            req.targetsList
                .sumOf { group -> group.maxTargets.takeIf { it >= group.minTargets } ?: group.minTargets }
                .coerceAtLeast(min)
        return min..max
    }

    /** Committed target instanceIds as reflected by the (re-)prompt. */
    fun committedTargets(req: SelectTargetsReq): Set<Int> =
        req.targetsList
            .asSequence()
            .flatMap { it.targetsList.asSequence() }
            .filter { it.legalAction == SelectAction.Unselect }
            .map { it.targetInstanceId }
            .toSet()

    /**
     * Next target-declaration step: select every desired target not yet
     * committed, else unselect any committed target no longer desired, else
     * Submit. Selecting the whole missing set at once is legal — the engine
     * accumulates taps — and keeps the walk to at most select-then-submit.
     * Each toggle carries the target group's `targetIdx` so a stricter host
     * binds the pick to the right requirement.
     */
    fun step(
        req: SelectTargetsReq,
        committed: Set<Int>,
        desired: List<Int>,
    ): SimDecision {
        val missing = desired.filter { it !in committed }
        if (missing.isNotEmpty()) return SimDecision.SelectTargets(missing, groupIdxFor(req, missing))
        val extra = committed.filter { it !in desired }
        if (extra.isNotEmpty()) return SimDecision.UnselectTargets(extra, groupIdxFor(req, extra))
        return SimDecision.SubmitTargets
    }

    /** targetIdx of the prompt group whose legal targets include any of [ids]. */
    private fun groupIdxFor(
        req: SelectTargetsReq,
        ids: List<Int>,
    ): Int =
        req.targetsList
            .firstOrNull { group -> group.targetsList.any { it.targetInstanceId in ids } }
            ?.targetIdx ?: 0
}
