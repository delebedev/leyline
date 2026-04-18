package leyline.game.snapshot

import leyline.bridge.SeatId

/**
 * Immutable per-seat state read by mappers. Field set grows as mappers migrate;
 * the minimal set here covers [leyline.game.mapper.PlayerMapper].
 */
data class SeatSnapshot(
    val seatId: SeatId,
    val life: Int,
    val startingLife: Int,
    val maxHandSize: Int,
)
