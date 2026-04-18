package leyline.game.snapshot

import forge.game.Game
import leyline.bridge.ForgeCardId
import leyline.game.GameBridge
import org.jetbrains.annotations.VisibleForTesting

/**
 * Immutable capture of every field the GSM pipeline reads from the engine.
 * Captured once per bundle at entry; every downstream stage is a pure function of it.
 *
 * Field set grows as mappers migrate — see this bundle's plan for migration order.
 */
class GsmSnapshot internal constructor(
    val matchId: String,
    val seats: List<SeatSnapshot>,
    val zones: Map<Int, ZoneSnapshot>,
    val objects: Map<ForgeCardId, CardSnapshot>,
    val stack: StackSnapshot,
    val phase: PhaseSnapshot,
    val combat: CombatSnapshot?,
    val capturedAt: CaptureMarker,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GsmSnapshot) return false
        // CaptureMarker excluded — wallClock is non-deterministic.
        return matchId == other.matchId &&
            seats == other.seats &&
            zones == other.zones &&
            objects == other.objects &&
            stack == other.stack &&
            phase == other.phase &&
            combat == other.combat
    }

    override fun hashCode(): Int {
        var h = matchId.hashCode()
        h = 31 * h + seats.hashCode()
        h = 31 * h + zones.hashCode()
        h = 31 * h + objects.hashCode()
        h = 31 * h + stack.hashCode()
        h = 31 * h + phase.hashCode()
        h = 31 * h + (combat?.hashCode() ?: 0)
        return h
    }

    companion object {
        /** Production capture — reads game + bridge. */
        fun capture(game: Game, bridge: GameBridge, matchId: String): GsmSnapshot =
            SnapshotCapture.run(game, bridge, matchId)

        /** Test fixture builder — named args with sensible defaults. */
        @VisibleForTesting
        fun forTest(
            matchId: String = "test-match",
            seats: List<SeatSnapshot> = emptyList(),
            zones: Map<Int, ZoneSnapshot> = emptyMap(),
            objects: Map<ForgeCardId, CardSnapshot> = emptyMap(),
            stack: StackSnapshot = StackSnapshot(emptyList()),
            phase: PhaseSnapshot = PhaseSnapshot(
                turn = 1,
                activePlayer = leyline.bridge.SeatId(1),
                priorityPlayer = leyline.bridge.SeatId(1),
                phase = null,
            ),
            combat: CombatSnapshot? = null,
            capturedAt: CaptureMarker = CaptureMarker.unknown(),
        ): GsmSnapshot = GsmSnapshot(matchId, seats, zones, objects, stack, phase, combat, capturedAt)
    }
}
