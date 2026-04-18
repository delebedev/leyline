package leyline.game.snapshot

import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId

data class StackSnapshot(val entries: List<StackEntry>)

data class StackEntry(
    val forgeCardId: ForgeCardId,
    val controller: SeatId,
    val targets: List<ForgeCardId>,
)
