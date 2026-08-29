package leyline.match

import leyline.bridge.types.SeatId
import leyline.game.state.GameBridge

internal sealed interface HandlerResult {
    data object NotHandled : HandlerResult

    data object Waiting : HandlerResult

    data object Resume : HandlerResult

    val resumes: Boolean
        get() = this === Resume
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
        completedActionId: String?,
        afterEngineResume: (() -> Unit)? = null,
        timeoutMs: Long = bridge.priorityWaitMs,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        if (!awaitNextSeatHorizon(deadline, completedActionId)) return
        afterEngineResume?.invoke()
        deliverHorizon()
    }

    private fun awaitNextSeatHorizon(
        deadline: Long,
        completedActionId: String?,
    ): Boolean {
        val remainingMs = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
        if (System.nanoTime() >= deadline ||
            !bridge.awaitSeatHorizonWithTimeout(seatId, remainingMs, ignoredActionId = completedActionId)
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
        if (bridge.cutCoordinator.committedGameOverOutcome() == null || terminalDelivered) return
        terminalDelivered = true
        sink.sendGameOver()
    }
}
