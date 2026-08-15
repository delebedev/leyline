package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId

/** Exact callback source that capture must freeze before a PayCosts prompt is materialized. */
sealed interface PayCostsPromptSourceInput {
    data class StackAbility(
        val forgeAbilityId: Int,
        val sourceForgeCardId: ForgeCardId,
        val abilityDefinitionId: Int,
        val targetForgeCardIds: List<ForgeCardId>,
    ) : PayCostsPromptSourceInput

    data class StackCard(
        val forgeCardId: ForgeCardId,
    ) : PayCostsPromptSourceInput
}

/** Value-only stack identity frozen for a PayCosts prompt. */
sealed interface PayCostsPromptSourceValue {
    data class StackAbility(
        val forgeAbilityId: Int,
        val sourceForgeCardId: ForgeCardId,
        val abilityGrpId: Int,
        val sourceCardGrpId: Int,
        val ownerSeatId: Int,
        val controllerSeatId: Int,
        val targetForgeCardIds: List<ForgeCardId>,
    ) : PayCostsPromptSourceValue

    data class StackCard(
        val forgeCardId: ForgeCardId,
    ) : PayCostsPromptSourceValue
}

/** One immutable option in a coordinator-owned one-shot PayCosts window. */
data class OneShotPayCostsCandidateValue(
    val originalOptionIndex: Int,
    val forgeCardId: ForgeCardId,
    val weight: Int,
)

/** Immutable materialization input for one non-iterative PayCosts request. */
data class OneShotPayCostsWindowValue(
    val kind: PayCostsRouteKind,
    val candidates: List<OneShotPayCostsCandidateValue>,
    val sourceForgeCardId: ForgeCardId?,
    val promptSource: PayCostsPromptSourceValue?,
    val minSelections: Int,
    val maxSelections: Int,
    val minimumWeight: Int?,
    val tapPayment: TapPaymentDescriptor?,
    val defaultOptionIndex: Int,
)

/** Current client-correlated identity of a one-shot PayCosts window. */
data class PublishedOneShotPayCostsInteraction(
    val interactionId: String,
    val gameStateId: Int,
    val kind: PayCostsRouteKind,
)

class OneShotPayCostsTimeoutException : RuntimeException("One-shot PayCosts interaction timed out")
