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

/** Counter family admitted by the grounded GatherCounters payment row. */
enum class GatherCounterType {
    P1P1,
}

/** Immutable source capacity frozen while Forge still owns the payment callback. */
data class GatherCountersSourceValue(
    val forgeCardId: ForgeCardId,
    val maxAmount: Int,
)

/** Engine callback facts before stack-source metadata is resolved for projection. */
data class GatherCountersWindowInput(
    val promptSource: PayCostsPromptSourceInput.StackAbility,
    val sources: List<GatherCountersSourceValue>,
    val amountToGather: Int,
    val counterType: GatherCounterType,
)

/** Immutable materialization input for the bounded GatherCounters payment row. */
data class GatherCountersWindowValue(
    val promptSource: PayCostsPromptSourceValue.StackAbility,
    val sources: List<GatherCountersSourceValue>,
    val amountToGather: Int,
    val counterType: GatherCounterType,
) {
    init {
        require(amountToGather == 2) { "Only the grounded two-counter GatherCounters row is supported" }
        require(counterType == GatherCounterType.P1P1) { "Only +1/+1 GatherCounters are supported" }
        require(sources.isNotEmpty()) { "GatherCounters requires at least one source" }
        require(sources.distinctBy { it.forgeCardId }.size == sources.size) {
            "GatherCounters sources require unique Forge card ids"
        }
        require(sources.all { it.maxAmount > 0 }) { "GatherCounters source capacities must be positive" }
        require(sources.sumOf { it.maxAmount } >= amountToGather) {
            "GatherCounters sources cannot satisfy the required amount"
        }
    }
}

/** Typed one-shot window family beneath the shared PayCosts lifecycle. */
sealed interface OneShotPayCostsWindow {
    val sourceForgeCardId: ForgeCardId?

    data class Select(
        val value: OneShotPayCostsWindowValue,
    ) : OneShotPayCostsWindow {
        override val sourceForgeCardId: ForgeCardId? get() = value.sourceForgeCardId
    }

    data class GatherCounters(
        val value: GatherCountersWindowValue,
    ) : OneShotPayCostsWindow {
        override val sourceForgeCardId: ForgeCardId get() = value.promptSource.sourceForgeCardId
    }
}

/** Current client-correlated identity of a one-shot PayCosts window. */
data class PublishedOneShotPayCostsInteraction(
    val interactionId: String,
    val gameStateId: Int,
    val windowKind: OneShotPayCostsWindowKind = OneShotPayCostsWindowKind.Select,
    val selectKind: PayCostsRouteKind? = null,
) {
    init {
        require((windowKind == OneShotPayCostsWindowKind.Select) == (selectKind != null)) {
            "Published one-shot window kind and Select route kind must agree"
        }
    }

    /** Select-only compatibility view; GatherCounters intentionally has no Select route kind. */
    val kind: PayCostsRouteKind
        get() = checkNotNull(selectKind) { "GatherCounters interaction has no Select route kind" }
}

enum class OneShotPayCostsWindowKind {
    Select,
    GatherCounters,
}

class OneShotPayCostsTimeoutException : RuntimeException("One-shot PayCosts interaction timed out")

/** Client response facts accepted by the GatherCounters runtime. */
data class GatherCountersSelection(
    val instanceId: Int,
    val amount: Int,
)
