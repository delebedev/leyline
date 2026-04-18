package leyline.game.snapshot

import forge.game.Game
import leyline.bridge.SeatId
import leyline.game.GameBridge

/**
 * Produces a [GsmSnapshot] by reading [Game] + [GameBridge]. This is the only
 * place in the pipeline (aside from [leyline.game.BundleBuilder]'s capture call)
 * that reads `forge.game.Game` directly. Each mapper migration grows the capture
 * to cover the newly-migrated stage's reads.
 *
 * Task 1: bare skeleton — matchId + empty collections.
 * Task 2: populates [GsmSnapshot.seats] for seats 1 and 2.
 *   Later tasks populate each section as the corresponding mapper migrates.
 */
@Suppress("UNUSED_PARAMETER")
internal object SnapshotCapture {
    fun run(game: Game, bridge: GameBridge, matchId: String): GsmSnapshot {
        val seats = listOf(1, 2).mapNotNull { seatNum ->
            val player = bridge.getPlayer(SeatId(seatNum)) ?: return@mapNotNull null
            SeatSnapshot(
                seatId = SeatId(seatNum),
                life = player.life,
                startingLife = player.startingLife,
                maxHandSize = player.maxHandSize,
            )
        }
        return GsmSnapshot.forTest(
            matchId = matchId,
            seats = seats,
            capturedAt = CaptureMarker(
                gsIdBeforeCapture = -1,
                wallClockMs = System.currentTimeMillis(),
            ),
        )
    }
}
