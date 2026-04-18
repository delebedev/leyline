package leyline.game.snapshot

import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

data class ZoneSnapshot(
    val id: Int,
    val type: ZoneType,
    val owner: SeatId?,
    val visibility: Visibility,
    val contents: List<ForgeCardId>,
)
