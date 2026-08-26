package leyline.match

import leyline.bridge.handoff.ModalChoiceCleanupToken
import leyline.bridge.handoff.PendingActionKind
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
        }
        continueUntilVisible(deadline)
    }

    private fun continueUntilVisible(deadline: Long) {
        while (true) {
            if (bridge.gameIsOver()) {
                sendGameOverIfTerminal()
                return
            }
            if (bridge.hasPendingNonActionInteraction()) return
            val pending = bridge.actionBridge(seatId).getPending()
            if (pending != null && pending.state.kind != PendingActionKind.SYNC_ONLY) {
                if (!bridge.cutCoordinator.isPassOnlyPriority(pending.actionId)) return
                check(bridge.cutCoordinator.continuePassOnly(pending.actionId)) {
                    "Non-visible runtime horizon changed before continuation"
                }
            }
            if (!awaitCommittedFeed(deadline)) return
            deliverHorizon()
        }
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

    private fun awaitCommittedFeed(deadline: Long): Boolean {
        while (true) {
            if (bridge.cutCoordinator.hasCommittedBatches(seatId)) return true
            if (bridge.gameIsOver()) {
                sendGameOverIfTerminal()
                return false
            }
            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
            if (System.nanoTime() >= deadline || !bridge.awaitSeatHorizonWithTimeout(seatId, remainingMs)) {
                sendGameOverIfTerminal()
                return false
            }
            return true
        }
    }

    /** Wait through engine-owned synchronization horizons until a client window exists. */
    fun awaitClientVisibleHorizon(timeoutMs: Long = bridge.priorityWaitMs) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
            if (!bridge.awaitSeatHorizonWithTimeout(seatId, remainingMs)) {
                sendGameOverIfTerminal()
                return
            }
            deliverHorizon()
            val pending = bridge.actionBridge(seatId).getPending()
            if (pending == null || bridge.gameIsOver()) return
            if (pending.state.kind != PendingActionKind.SYNC_ONLY) {
                if (!bridge.cutCoordinator.isPassOnlyPriority(pending.actionId)) return
                check(bridge.cutCoordinator.continuePassOnly(pending.actionId)) {
                    "Non-visible runtime horizon changed before continuation"
                }
            }
            if (System.nanoTime() >= deadline) return
        }
    }

    /** Bind the initial feed and let the coordinator-owned pass-only window continue. */
    fun bindInitialHorizon(actionId: String) {
        val visible = bridge.cutCoordinator.hasMeaningfulPriorityAction(actionId)
        bridge.cutCoordinator.replaceWithPhaseTransition(actionId, includePriorityPrompt = visible)
        if (!visible && bridge.cutCoordinator.isPassOnlyPriority(actionId)) {
            check(bridge.cutCoordinator.continuePassOnly(actionId)) {
                "Initial runtime horizon changed before the coordinator could continue it"
            }
        }
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
