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

data class ForageFoodPlan(
    val requiredCount: Int = 1,
) {
    fun toCardSelectionPlan(): CostCardSelectionPlan = CostCardSelectionPlan(PromptSemantic.SelectNCostSacrifice)
}

data class TapTypeCostPlan(
    val minSelection: Int,
    val maxSelection: Int,
    val isStation: Boolean,
) {
    fun toCardSelectionPlan(): CostCardSelectionPlan =
        CostCardSelectionPlan(if (isStation) PromptSemantic.StationTapCost else PromptSemantic.Generic)
}

data class EnlistCostPlan(
    val requiredCount: Int,
) {
    fun toCardSelectionPlan(): CostCardSelectionPlan = CostCardSelectionPlan(PromptSemantic.EnlistCost)
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

data class ForageGraveyardExilePlan(
    val requiredCount: Int,
) {
    fun toCardSelectionPlan(): CostCardSelectionPlan = CostCardSelectionPlan(PromptSemantic.Generic)
}

data class ForageCostPlan(
    val foodSacrifice: ForageFoodPlan?,
    val graveyardExile: ForageGraveyardExilePlan?,
) {
    val canSacrificeFood: Boolean = foodSacrifice != null
    val canExileFromGraveyard: Boolean = graveyardExile != null
}

object CostDecisionPlanner {
    fun collectEvidencePlan(
        total: Int,
        manaValues: List<Int>,
    ): CollectEvidenceCostPlan = CollectEvidenceCostPlan(total, manaValues)

    fun tapTypePlan(
        minSelection: Int,
        maxSelection: Int,
        isStation: Boolean,
    ): TapTypeCostPlan = TapTypeCostPlan(minSelection, maxSelection, isStation)

    fun enlistPlan(requiredCount: Int): EnlistCostPlan = EnlistCostPlan(requiredCount)

    fun teamworkPlan(
        totalPower: Int,
        powers: List<Int>,
    ): TeamworkCostPlan = TeamworkCostPlan(totalPower, powers)

    fun foragePlan(
        foodCount: Int,
        graveyardExileCount: Int,
    ): ForageCostPlan =
        ForageCostPlan(
            foodSacrifice = if (foodCount > 0) ForageFoodPlan() else null,
            graveyardExile =
                if (graveyardExileCount >= FORAGE_GRAVEYARD_EXILE_COUNT) {
                    ForageGraveyardExilePlan(FORAGE_GRAVEYARD_EXILE_COUNT)
                } else {
                    null
                },
        )

    private const val FORAGE_GRAVEYARD_EXILE_COUNT = 3
}
