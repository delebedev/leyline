package leyline.bridge.interaction

import leyline.bridge.handoff.PromptSemantic

data class CostCardSelectionPlan(
    val semantic: PromptSemantic,
    val costSelectionWeights: List<Int> = emptyList(),
    val minSelectionWeight: Int? = null,
)

data class TapTypeCostPlan(
    val minSelection: Int,
    val maxSelection: Int,
    val isStation: Boolean,
) {
    fun toCardSelectionPlan(): CostCardSelectionPlan =
        CostCardSelectionPlan(if (isStation) PromptSemantic.StationTapCost else PromptSemantic.Generic)
}

data class TeamworkCostPlan(
    val totalPower: Int,
    val powers: List<Int>,
) {
    fun toCardSelectionPlan(): CostCardSelectionPlan =
        CostCardSelectionPlan(
            semantic = PromptSemantic.TeamworkCost,
            costSelectionWeights = powers.map { it.coerceAtLeast(0) },
            minSelectionWeight = totalPower,
        )
}

object CostDecisionPlanner {
    fun tapTypePlan(
        minSelection: Int,
        maxSelection: Int,
        isStation: Boolean,
    ): TapTypeCostPlan = TapTypeCostPlan(minSelection, maxSelection, isStation)

    fun teamworkPlan(
        totalPower: Int,
        powers: List<Int>,
    ): TeamworkCostPlan = TeamworkCostPlan(totalPower, powers)
}
