package leyline.bridge.coord

import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.SeatId
import java.util.concurrent.TimeoutException

/** Per-seat engine wait adapter over the match-scoped action-window owner. */
internal class CoordinatorActionWindowBridge(
    private val runtime: MatchActionWindowRuntime,
    private val seatId: SeatId,
) : GameActionBridge.ActionWindowRuntime {
    override fun publish(pending: GameActionBridge.PendingAction) = runtime.publish(seatId, pending)

    override fun isVisible(actionId: String): Boolean = runtime.isVisible(actionId)

    override fun promptGameStateId(actionId: String): Int? = runtime.promptGameStateId(actionId)

    override fun resolve(
        pending: GameActionBridge.PendingAction,
        submission: GameActionBridge.ActionSubmission.RuntimeToken,
    ): PlayerAction? = runtime.resolve(pending, submission)

    override fun close(
        pending: GameActionBridge.PendingAction,
        reason: GameActionBridge.WindowCloseReason,
    ) = runtime.close(pending.actionId)

    override fun claimTimeout(
        pending: GameActionBridge.PendingAction,
        cause: TimeoutException,
    ): Boolean = runtime.claimTimeout(pending, cause)

    override fun claimSynchronizationTimeout(
        pending: GameActionBridge.PendingAction,
        cause: TimeoutException,
    ): Boolean = runtime.claimSynchronizationTimeout(pending, cause)

    override fun completeSynchronization(pending: GameActionBridge.PendingAction): Boolean = runtime.completeSynchronization(pending)
}
