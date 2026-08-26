package leyline.match

import leyline.bridge.types.SeatId
import leyline.game.state.GameBridge

internal enum class HandlerResult {
    NotHandled,
    Waiting,
    Resume,
}

/**
 * Mechanical transport seam for one engine horizon.
 *
 * The engine owns progression and priority policy. The transport only waits
 * for the next published horizon, drains committed output in order, and
 * releases exact state-only barriers after delivery.
 */
internal class MatchRuntimeContinuation(
    private val sink: GreMessageSink,
    private val bridge: GameBridge,
    private val seatId: SeatId,
) {
    private var terminalDelivered = false

    fun awaitHorizon(timeoutMs: Long = bridge.priorityWaitMs) {
        if (!bridge.awaitPriorityWithTimeout(timeoutMs)) {
            sendGameOverIfTerminal()
            return
        }
        drainCoordinatorBarrier(sink, bridge, seatId)
        sendGameOverIfTerminal()
    }

    private fun sendGameOverIfTerminal() {
        if (!bridge.gameIsOver() || bridge.actionBridge(seatId).getPending() != null || terminalDelivered) return
        terminalDelivered = true
        sink.sendGameOver()
    }
}
