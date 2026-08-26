package leyline.match

import leyline.bridge.handoff.ModalChoiceCleanupToken
import leyline.bridge.types.SeatId
import leyline.game.state.GameBridge

internal sealed interface HandlerResult {
    data object NotHandled : HandlerResult

    data object Waiting : HandlerResult

    data object Resume : HandlerResult

    data class ResumeAfterEngineResume(
        val cleanup: ModalChoiceCleanupToken,
    ) : HandlerResult

    val resumes: Boolean
        get() = this === Resume || this is ResumeAfterEngineResume
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

    fun awaitHorizon(
        result: HandlerResult = HandlerResult.Resume,
        timeoutMs: Long = bridge.priorityWaitMs,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        if (result is HandlerResult.ResumeAfterEngineResume) {
            if (!awaitNextSeatHorizon(deadline)) return
            bridge.cutCoordinator.modalChoices.releaseAfterEngineResume(result.cleanup)
            deliverHorizon()
            return
        }
        if (!awaitNextSeatHorizon(deadline)) return
        deliverHorizon()
    }

    private fun awaitNextSeatHorizon(deadline: Long): Boolean {
        val remainingMs = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
        val responseActionId = bridge.actionBridge(seatId).getPending()?.actionId
        if (System.nanoTime() >= deadline ||
            !bridge.awaitSeatHorizonWithTimeout(seatId, remainingMs, ignoredActionId = responseActionId)
        ) {
            sendGameOverIfTerminal()
            return false
        }
        return true
    }

    /** Deliver the first bound horizon, including any engine-owned sync barriers. */
    fun awaitClientVisibleHorizon(
        ignoredActionId: String? = null,
        timeoutMs: Long = bridge.priorityWaitMs,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        if (!bridge.cutCoordinator.hasCommittedBatches(seatId)) {
            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
            val awaited = bridge.awaitSeatHorizonWithTimeout(seatId, remainingMs, ignoredActionId)
            if (System.nanoTime() >= deadline || !awaited) {
                sendGameOverIfTerminal()
                return
            }
        }
        deliverHorizon()
    }

    fun deliverHorizon() {
        drainCoordinatorBarrier(sink, bridge, seatId)
        sendGameOverIfTerminal()
    }

    private fun sendGameOverIfTerminal() {
        if (!bridge.gameIsOver() || bridge.actionBridge(seatId).getPending() != null || terminalDelivered) return
        terminalDelivered = true
        sink.sendGameOver()
    }
}
