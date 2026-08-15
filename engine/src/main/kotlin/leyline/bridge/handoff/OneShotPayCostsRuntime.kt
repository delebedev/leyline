package leyline.bridge.handoff

import forge.game.card.Card

/** Exact original Forge handle and amount selected for GatherCounters. */
data class GatherCountersPayment(
    val handle: Card,
    val amount: Int,
)

data class GatherCountersResult(
    val payments: List<GatherCountersPayment>,
    /** True when the match-scoped window retired through its deterministic timeout default. */
    val timedOut: Boolean = false,
) {
    val isEmpty: Boolean get() = payments.isEmpty()

    companion object {
        val EMPTY = GatherCountersResult(emptyList())
    }
}

/** Deterministic option-order default used when the engine cannot wait for a client response. */
internal fun GatherCountersWindowInput.firstFitResult(
    candidateHandles: List<Card>,
    timedOut: Boolean = false,
): GatherCountersResult {
    val handlesById = candidateHandles.associateBy { it.id }
    if (handlesById.size != candidateHandles.size ||
        amountToGather <= 0 ||
        counterType != GatherCounterType.P1P1 ||
        sources.isEmpty() ||
        sources.distinctBy { it.forgeCardId }.size != sources.size
    ) {
        return GatherCountersResult(emptyList(), timedOut)
    }
    return firstFitGatherCounters(sources, amountToGather, handlesById, timedOut)
}

internal fun firstFitGatherCounters(
    sources: List<GatherCountersSourceValue>,
    amountToGather: Int,
    handlesBySourceId: Map<Int, Card>,
    timedOut: Boolean,
): GatherCountersResult {
    if (amountToGather <= 0 || sources.any { it.maxAmount <= 0 }) return GatherCountersResult(emptyList(), timedOut)
    var remaining = amountToGather
    val payments =
        sources.mapNotNull { source ->
            val handle = handlesBySourceId[source.forgeCardId.value] ?: return GatherCountersResult(emptyList(), timedOut)
            if (remaining == 0) return@mapNotNull null
            val amount = source.maxAmount.coerceAtMost(remaining)
            remaining -= amount
            GatherCountersPayment(handle, amount)
        }
    return if (remaining == 0) GatherCountersResult(payments, timedOut) else GatherCountersResult(emptyList(), timedOut)
}

/** Blocking engine-thread shell contract for one-shot Select and GatherCounters PayCosts routes. */
interface OneShotPayCostsRuntime {
    fun awaitPayment(
        request: PromptRequest,
        candidateHandles: List<Card>,
        timeoutMs: Long?,
    ): OneShotPayCostsResult

    fun awaitGatherCounters(
        window: GatherCountersWindowInput,
        candidateHandles: List<Card>,
        timeoutMs: Long?,
    ): GatherCountersResult
}

/** Exact original option handles selected by a completed one-shot PayCosts window. */
data class OneShotPayCostsResult(
    val optionIndices: List<Int>,
    val handles: List<Card>,
) : List<Int> by optionIndices
