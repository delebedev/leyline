package leyline.game.snapshot

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId

/** Client-visible state for a delayed or recurring trigger that has not fired yet. */
data class PendingTriggerSnapshot(
    /** Forge trigger identity used to correlate the later stack ability. */
    val runtimeTriggerId: Int? = null,
    /** Stable synthetic identity used to allocate the TriggerHolder instance id. */
    val holderForgeId: ForgeCardId,
    val owner: SeatId,
    val sourceForgeCardId: ForgeCardId,
    val parentInstanceId: Int,
    val sourceAbilityGrpId: Int,
    val cleanupAbilityGrpId: Int,
    val affectedCardIds: List<ForgeCardId> = emptyList(),
    /** Exile-and-return relations also display each affected card under the holder. */
    val displaysAffectedCards: Boolean = false,
)
