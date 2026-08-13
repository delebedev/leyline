package leyline.copilot

import wotc.mtgo.gre.external.messaging.Messages.SelectAction
import wotc.mtgo.gre.external.messaging.Messages.SelectTargetsReq

internal typealias TargetGroupSelections = Map<Int, List<Int>>

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
    /** Committed target instanceIds per request group, as reflected by the (re-)prompt. */
    fun committedTargets(req: SelectTargetsReq): TargetGroupSelections =
        req.targetsList.associate { group ->
            group.targetIdx to
                group.targetsList
                    .filter { it.legalAction == SelectAction.Unselect }
                    .map { it.targetInstanceId }
                    .distinct()
        }

    /** Every request group independently satisfies its bounds and candidate set. */
    fun isValid(
        req: SelectTargetsReq,
        selections: TargetGroupSelections,
    ): Boolean =
        req.targetsList.all { group ->
            val selected = selections[group.targetIdx].orEmpty()
            val legalIds = group.targetsList.map { it.targetInstanceId }.toSet()
            selected.size == selected.distinct().size &&
                selected.all { it in legalIds } &&
                selected.size in group.minTargets.coerceAtLeast(0)..effectiveMax(group.minTargets, group.maxTargets)
        }

    /**
     * Next target-declaration step. Each response is one tap in one request
     * group; the next step waits for the host's fresh prompt before continuing.
     * Extras are removed before missing targets are added so an over-full
     * group converges back inside its bound before another group changes.
     */
    fun step(
        req: SelectTargetsReq,
        committed: TargetGroupSelections,
        desired: TargetGroupSelections,
    ): SimDecision? {
        if (!isValid(req, desired)) return null

        for (group in req.targetsList) {
            val desiredIds = desired[group.targetIdx].orEmpty()
            val extra = committed[group.targetIdx].orEmpty().firstOrNull { it !in desiredIds }
            if (extra != null) return SimDecision.UnselectTargets(listOf(extra), group.targetIdx)
        }
        for (group in req.targetsList) {
            val committedIds = committed[group.targetIdx].orEmpty()
            val missing = desired[group.targetIdx].orEmpty().firstOrNull { it !in committedIds }
            if (missing != null) return SimDecision.SelectTargets(listOf(missing), group.targetIdx)
        }
        return SimDecision.SubmitTargets.takeIf { isValid(req, committed) }
    }

    private fun effectiveMax(
        min: Int,
        max: Int,
    ): Int = max.takeIf { it >= min } ?: min
}
