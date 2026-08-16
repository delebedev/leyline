package leyline.copilot

import leyline.game.event.FrameEventLog
import leyline.game.projectSnapshotForTest
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge

/** Builds a value-only projection for Copilot snapshot tests from one explicit cut. */
internal object StateMapper {
    @Suppress("UnusedParameter")
    fun buildFromSnapshot(
        snap: GsmSnapshot,
        gameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        viewingSeatId: Int,
        events: FrameEventLog = FrameEventLog.EMPTY,
    ) = bridge.projectSnapshotForTest(
        snap = snap,
        gameStateId = gameStateId,
        viewingSeatId = viewingSeatId,
        events = events,
    )
}
