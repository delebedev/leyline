package leyline.bridge.types

/**
 * Match-scoped seat role mapping. Which seat holds the human-controlled player
 * and which holds the Familiar/AI is determined at game-start by inspecting
 * `Player.lobbyPlayer` on the Forge side.
 *
 * Constructed once per match in `GameBridge.populateSeatMap` and exposed as
 * `gameBridge.seating`. Replaces hardcoded `seatId == 1` / `seatId == 2`
 * role gates throughout engine prod.
 *
 * This is a 2-player invariant; `humanSeat.opponent == familiarSeat` holds
 * by construction.
 */
data class Seating(
    val humanSeat: SeatId,
    val familiarSeat: SeatId,
)
