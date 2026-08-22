package leyline.testkit

import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PlayerAction

/** Minimal action-window owner for tests that exercise the blocking bridge directly. */
class TestActionWindowRuntime(
    private val resolveAction: (Long) -> PlayerAction = { PlayerAction.PassPriority },
) : GameActionBridge.ActionWindowRuntime {
    @Volatile private var visibleActionId: String? = null

    override fun publish(pending: GameActionBridge.PendingAction) {
        visibleActionId = pending.actionId
    }

    override fun isVisible(actionId: String): Boolean = visibleActionId == actionId

    override fun promptGameStateId(actionId: String): Int? = null

    override fun resolve(
        pending: GameActionBridge.PendingAction,
        submission: GameActionBridge.ActionSubmission.RuntimeToken,
    ): PlayerAction =
        if (submission.token == GameActionBridge.ENGINE_PASS_TOKEN) PlayerAction.PassPriority else resolveAction(submission.token)

    override fun close(
        pending: GameActionBridge.PendingAction,
        reason: GameActionBridge.WindowCloseReason,
    ) {
        visibleActionId = null
    }
}
