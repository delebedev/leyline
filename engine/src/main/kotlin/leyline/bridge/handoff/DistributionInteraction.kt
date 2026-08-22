package leyline.bridge.handoff

/** Exact target identity and ordering recorded before a divided allocation prompt. */
data class DistributionWindowValue(
    val kind: DistributionRouteKind,
    val targetForgeIds: List<Int>,
    /** Target entries that are player seat ids rather than Forge card ids. */
    val targetSeatIds: Set<Int> = emptySet(),
    val amount: Int,
    val minPerTarget: Int,
    val sourceForgeCardId: Int,
    val sourceForgeAbilityId: Int,
    val sourceIsSpell: Boolean,
) {
    init {
        require(targetForgeIds.size >= 2) { "Distribution requires at least two targets" }
        require(amount > targetForgeIds.size) { "Distribution amount must exceed target count" }
        require(minPerTarget >= 1) { "Distribution requires a positive minimum" }
        require(targetForgeIds.distinct().size == targetForgeIds.size) { "Distribution targets must be distinct" }
        require(targetSeatIds.all { it in targetForgeIds }) { "Distribution seat targets must be present in target ids" }
    }

    fun fallback(): DistributionInteractionResult {
        val base = amount / targetForgeIds.size
        val remainder = amount % targetForgeIds.size
        return DistributionInteractionResult(
            targetForgeIds.mapIndexed { index, id -> id to base + if (index < remainder) 1 else 0 }.toMap(),
            timedOut = true,
        )
    }
}

data class DistributionInteractionResult(
    val amounts: Map<Int, Int>,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
)

class DistributionInteractionTimeoutException : RuntimeException("Distribution interaction timed out")

interface DistributionInteractionRuntime {
    fun awaitDistribution(
        request: PromptRequest,
        window: DistributionWindowValue,
        timeoutMs: Long?,
    ): DistributionInteractionResult
}

data class PublishedDistributionInteraction(
    val interactionId: String,
    val gameStateId: Int,
    val kind: DistributionRouteKind,
)
