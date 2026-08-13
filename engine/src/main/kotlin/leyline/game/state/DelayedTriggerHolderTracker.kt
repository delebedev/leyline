package leyline.game.state

import leyline.bridge.types.ForgeCardId

data class HolderRecord(
    val iid: Int,
    val ownerSeat: Int,
    val objectSourceGrpId: Int,
    val parentIid: Int,
    val cleanupGrpId: Int,
    val sourceForgeCardId: ForgeCardId? = null,
    val runtimeTriggerId: Int? = null,
)

data class HolderBatch(
    val added: List<HolderRecord>,
    val removed: List<Int>,
) {
    companion object {
        val EMPTY = HolderBatch(emptyList(), emptyList())
    }
}
