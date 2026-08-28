package leyline.match

import leyline.game.state.GameBridge

/** Deliver already-committed lifecycle batches through the connection-owned sink. */
internal fun SessionOps.deliverLifecycle(
    bridge: GameBridge,
    beforeMsgId: Int? = null,
) {
    val batches = bridge.cutCoordinator.drain(seatId, beforeMsgId = beforeMsgId)
    try {
        batches.forEach { batch ->
            if (this is MatchSession) sendLifecycleGRE(batch) else sendBundledGRE(batch)
        }
    } catch (ex: Exception) {
        bridge.cutCoordinator.failDelivery(ex)
    }
}
