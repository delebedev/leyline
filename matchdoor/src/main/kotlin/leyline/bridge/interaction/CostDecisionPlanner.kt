package leyline.bridge.interaction

import leyline.bridge.handoff.PromptSemantic

data class CostCardSelectionPlan(
    val semantic: PromptSemantic,
    val costSelectionWeights: List<Int> = emptyList(),
    val minSelectionWeight: Int? = null,
)

data class CollectEvidenceCostPlan(
    val total: Int,
    val manaValues: List<Int>,
) {
    fun toCardSelectionPlan(): CostCardSelectionPlan =
        CostCardSelectionPlan(
            semantic = PromptSemantic.SelectNCostCollectEvidence,
            costSelectionWeights = manaValues.map { it.coerceAtLeast(0) },
            minSelectionWeight = total,
        )
}

object CostDecisionPlanner {
    fun collectEvidencePlan(
        total: Int,
        manaValues: List<Int>,
    ): CollectEvidenceCostPlan = CollectEvidenceCostPlan(total, manaValues)

    fun typedDiscard(): CostCardSelectionPlan = CostCardSelectionPlan(PromptSemantic.SelectNDiscard)

    fun enlist(): CostCardSelectionPlan = CostCardSelectionPlan(PromptSemantic.EnlistCost)

    fun sacrifice(): CostCardSelectionPlan = CostCardSelectionPlan(PromptSemantic.SelectNCostSacrifice)

    fun returnCost(
        type: String,
        descriptiveType: String,
    ): CostCardSelectionPlan =
        CostCardSelectionPlan(
            semantic =
                if (type.contains("attacking+unblocked") || descriptiveType.contains("unblocked attacker", ignoreCase = true)) {
                    PromptSemantic.ReturnUnblockedAttackerCost
                } else {
                    PromptSemantic.Generic
                },
        )

    fun tapType(isStation: Boolean): CostCardSelectionPlan =
        CostCardSelectionPlan(if (isStation) PromptSemantic.StationTapCost else PromptSemantic.Generic)
}
