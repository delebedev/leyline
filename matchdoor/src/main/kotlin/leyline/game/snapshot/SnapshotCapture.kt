package leyline.game.snapshot

import forge.game.Game
import leyline.game.GameBridge

/**
 * Produces a [GsmSnapshot] by reading [Game] + [GameBridge]. This is the only
 * place in the pipeline (aside from [leyline.game.BundleBuilder]'s capture call)
 * that reads `forge.game.Game` directly. Each mapper migration grows the capture
 * to cover the newly-migrated stage's reads.
 *
 * Task 1 (this task): returns a bare skeleton — matchId + empty collections.
 *   Later tasks populate each section as the corresponding mapper migrates.
 */
@Suppress("UNUSED_PARAMETER")
internal object SnapshotCapture {
    fun run(game: Game, bridge: GameBridge, matchId: String): GsmSnapshot {
        // Task 1 skeleton: minimal capture. Future tasks grow this.
        return GsmSnapshot.forTest(
            matchId = matchId,
            capturedAt = CaptureMarker(
                gsIdBeforeCapture = -1,
                wallClockMs = System.currentTimeMillis(),
            ),
        )
    }
}
