package leyline.game.snapshot

import forge.game.phase.PhaseType
import leyline.bridge.SeatId

/**
 * Phase/step + active player + priority player. `PhaseType` is a Forge enum
 * (value class, safe to hold in immutable data); it never references the live game.
 */
data class PhaseSnapshot(
    val turn: Int,
    val activePlayer: SeatId,
    val priorityPlayer: SeatId?,
    val phase: PhaseType?,
)
