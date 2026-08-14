package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId

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
    val sourceInstanceId: Int?,
    val minSelections: Int,
    val maxSelections: Int,
    val minimumWeight: Int?,
    val defaultOptionIndex: Int,
)

/** Current client-correlated identity of a one-shot PayCosts window. */
data class PublishedOneShotPayCostsInteraction(
    val interactionId: String,
    val gameStateId: Int,
    val kind: PayCostsRouteKind,
)

class OneShotPayCostsTimeoutException : RuntimeException("One-shot PayCosts interaction timed out")
