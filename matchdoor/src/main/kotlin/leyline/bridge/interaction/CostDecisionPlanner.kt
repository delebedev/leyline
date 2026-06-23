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

data class SacrificeCostPlan(
    val requiredCount: Int,
    val differentNames: Boolean = false,
) {
    fun toCardSelectionPlan(): CostCardSelectionPlan = CostCardSelectionPlan(PromptSemantic.SelectNCostSacrifice)
}

data class DiscardCostPlan(
    val requiredCount: Int,
    val discardType: String,
) {
    fun toCardSelectionPlan(): CostCardSelectionPlan = CostCardSelectionPlan(PromptSemantic.SelectNDiscard)
}

data class TapTypeCostPlan(
    val minSelection: Int,
    val maxSelection: Int,
    val isStation: Boolean,
) {
    fun toCardSelectionPlan(): CostCardSelectionPlan =
        CostCardSelectionPlan(if (isStation) PromptSemantic.StationTapCost else PromptSemantic.Generic)
}

object CostDecisionPlanner {
    fun collectEvidencePlan(
        total: Int,
        manaValues: List<Int>,
    ): CollectEvidenceCostPlan = CollectEvidenceCostPlan(total, manaValues)

    fun sacrificePlan(
        requiredCount: Int,
        differentNames: Boolean = false,
    ): SacrificeCostPlan = SacrificeCostPlan(requiredCount, differentNames)

    fun discardPlan(
        requiredCount: Int,
        discardType: String,
    ): DiscardCostPlan = DiscardCostPlan(requiredCount, discardType)

    fun tapTypePlan(
        minSelection: Int,
        maxSelection: Int,
        isStation: Boolean,
    ): TapTypeCostPlan = TapTypeCostPlan(minSelection, maxSelection, isStation)

    fun enlist(): CostCardSelectionPlan = CostCardSelectionPlan(PromptSemantic.EnlistCost)

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
}
