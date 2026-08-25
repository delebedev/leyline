package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId

sealed interface DistributionTargetRef {
    data class Card(
        val id: ForgeCardId,
    ) : DistributionTargetRef

    data class Player(
        val id: SeatId,
    ) : DistributionTargetRef
}

/** Exact target identity and ordering recorded before a divided allocation prompt. */
data class DistributionWindowValue(
    val kind: DistributionRouteKind,
    val targets: List<DistributionTargetRef>,
    val amount: Int,
    val minPerTarget: Int,
    val sourceForgeCardId: Int,
    val sourceForgeAbilityId: Int,
    val sourceIsSpell: Boolean,
) {
    init {
        require(targets.size >= 2) { "Distribution requires at least two targets" }
        require(minPerTarget >= 1) { "Distribution requires a positive minimum" }
        require(amount.toLong() > minPerTarget.toLong() * targets.size) {
            "Distribution amount must exceed the minimum for every target"
        }
        require(targets.distinct().size == targets.size) { "Distribution targets must be distinct" }
    }

    fun fallback(): DistributionInteractionResult {
        val base = amount / targets.size
        val remainder = amount % targets.size
        return DistributionInteractionResult(
            targets.mapIndexed { index, target -> target to base + if (index < remainder) 1 else 0 }.toMap(),
            timedOut = true,
        )
    }
}

data class DistributionInteractionResult(
    val amounts: Map<DistributionTargetRef, Int>,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
)

class DistributionInteractionTimeoutException : RuntimeException("Distribution interaction timed out")

interface DistributionInteractionRuntime {
    fun awaitDistribution(
        window: DistributionWindowValue,
        timeoutMs: Long?,
    ): DistributionInteractionResult
}

data class PublishedDistributionInteraction(
    val interactionId: String,
    val gameStateId: Int,
    val kind: DistributionRouteKind,
)
