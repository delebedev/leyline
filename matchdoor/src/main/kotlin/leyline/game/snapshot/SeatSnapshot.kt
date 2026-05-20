package leyline.game.snapshot

import leyline.bridge.types.SeatId
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

data class ManaPoolEntry(
    val manaId: Int,
    val color: ManaColor,
    val srcInstanceId: Int,
    val abilityGrpId: Int,
    val count: Int,
)

/**
 * Immutable per-seat state read by mappers. Field set grows as mappers migrate;
 * the minimal set here covers [leyline.game.mapping.PlayerMapper].
 */
data class SeatSnapshot(
    val seatId: SeatId,
    val life: Int,
    val startingLife: Int,
    val maxHandSize: Int,
    val manaPool: List<ManaPoolEntry> = emptyList(),
)
