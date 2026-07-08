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

data class ReturnCostPlan(
    val requiredCount: Int,
    val type: String,
    val descriptiveType: String,
) {
    val isUnblockedAttacker: Boolean =
        type.contains("attacking+unblocked") || descriptiveType.contains("unblocked attacker", ignoreCase = true)

    fun toCardSelectionPlan(): CostCardSelectionPlan =
        CostCardSelectionPlan(
            semantic = if (isUnblockedAttacker) PromptSemantic.ReturnUnblockedAttackerCost else PromptSemantic.Generic,
        )
}

data class ForageGraveyardExilePlan(
    val requiredCount: Int,
) {
    fun toCardSelectionPlan(): CostCardSelectionPlan = CostCardSelectionPlan(PromptSemantic.Generic)
}

data class ForageCostPlan(
    val foodSacrifice: SacrificeCostPlan?,
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

    fun enlistPlan(requiredCount: Int): EnlistCostPlan = EnlistCostPlan(requiredCount)

    fun teamworkPlan(
        totalPower: Int,
        powers: List<Int>,
    ): TeamworkCostPlan = TeamworkCostPlan(totalPower, powers)

    fun returnCostPlan(
        requiredCount: Int,
        type: String,
        descriptiveType: String,
    ): ReturnCostPlan = ReturnCostPlan(requiredCount, type, descriptiveType)

    fun foragePlan(
        foodCount: Int,
        graveyardExileCount: Int,
    ): ForageCostPlan =
        ForageCostPlan(
            foodSacrifice = if (foodCount > 0) sacrificePlan(requiredCount = 1) else null,
            graveyardExile =
                if (graveyardExileCount >= FORAGE_GRAVEYARD_EXILE_COUNT) {
                    ForageGraveyardExilePlan(FORAGE_GRAVEYARD_EXILE_COUNT)
                } else {
                    null
                },
        )

    private const val FORAGE_GRAVEYARD_EXILE_COUNT = 3
}
