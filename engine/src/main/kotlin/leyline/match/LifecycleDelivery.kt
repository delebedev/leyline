package leyline.match

import leyline.game.state.GameBridge

/** Deliver already-committed lifecycle batches through the connection-owned sink. */
internal fun SessionOps.deliverLifecycle(
    bridge: GameBridge,
    beforeMsgId: Int? = null,
) {
    bridge.cutCoordinator.drain(seatId, beforeMsgId = beforeMsgId).forEach { batch ->
        if (this is MatchSession) sendLifecycleGRE(batch) else sendBundledGRE(batch)
    }
}
