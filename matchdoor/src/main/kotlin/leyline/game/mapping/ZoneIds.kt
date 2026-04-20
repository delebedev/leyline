package leyline.game.mapping

import leyline.bridge.types.SeatId

/** Protocol zone IDs matching expected protocol layout (starting at 18). */
object ZoneIds {
    const val REVEALED_P1 = 18
    const val REVEALED_P2 = 19
    const val SUPPRESSED = 24
    const val PENDING = 25
    const val COMMAND = 26
    const val STACK = 27
    const val BATTLEFIELD = 28
    const val EXILE = 29
    const val LIMBO = 30
    const val P1_HAND = 31
    const val P1_LIBRARY = 32
    const val P1_GRAVEYARD = 33
    const val P1_SIDEBOARD = 34
    const val P2_HAND = 35
    const val P2_LIBRARY = 36
    const val P2_GRAVEYARD = 37
    const val P2_SIDEBOARD = 38

    fun handOf(seatId: Int): Int = if (seatId == 1) P1_HAND else P2_HAND
    fun libraryOf(seatId: Int): Int = if (seatId == 1) P1_LIBRARY else P2_LIBRARY
    fun graveyardOf(seatId: Int): Int = if (seatId == 1) P1_GRAVEYARD else P2_GRAVEYARD

    fun handOf(seat: SeatId): Int = handOf(seat.value)
    fun libraryOf(seat: SeatId): Int = libraryOf(seat.value)
    fun graveyardOf(seat: SeatId): Int = graveyardOf(seat.value)
}
