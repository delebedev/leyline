package leyline.match

import leyline.bridge.types.SeatId
import org.slf4j.LoggerFactory

/**
 * Owns asynchronous delivery of committed runtime horizons for one connection.
 *
 * The observer never submits an engine action. It only waits for a coordinator
 * feed notification, enters the session delivery boundary, and drains output.
 */
internal class MatchRuntimeDeliveryObserver(
    private val session: MatchSession,
    private val seatId: SeatId,
    private val generation: MatchRuntimeDeliveryGeneration,
) {
    private val log = LoggerFactory.getLogger(MatchRuntimeDeliveryObserver::class.java)

    @Volatile
    private var stopped = true
    private var worker: Thread? = null

    internal val isAlive: Boolean
        get() = worker?.isAlive == true

    @Synchronized
    fun start() {
        if (!stopped) return
        stopped = false
        worker =
            Thread(::run, "match-runtime-delivery-${seatId.value}").apply {
                isDaemon = true
                start()
            }
    }

    @Synchronized
    fun stop() {
        generation.invalidate()
        if (stopped) return
        stopped = true
        session
            .gameBridge
            .cutCoordinator
            .deliverySignal
            .signal()
    }

    private fun run() {
        while (isCurrent()) {
            val coordinator = session.gameBridge.cutCoordinator
            if (!coordinator.hasCommittedBatches(seatId)) {
                coordinator.deliverySignal.await(1_000)
                continue
            }
            try {
                deliverIfCurrent()
            } catch (ex: Throwable) {
                log
                    .atError()
                    .setCause(ex)
                    .addKeyValue("event", "match.runtime_delivery_failed")
                    .addKeyValue("match_id", session.matchId)
                    .addKeyValue("seat", seatId.value)
                    .log("Runtime horizon delivery failed")
                return
            }
        }
    }

    private fun deliverIfCurrent(): Boolean {
        synchronized(session.connection.sessionLock) {
            if (!isCurrent()) return false
            session.deliverRuntimeHorizon()
            return true
        }
    }

    private fun isCurrent(): Boolean = !stopped && generation.isActive
}

/** Invalidated before a replacement observer can become visible. */
internal class MatchRuntimeDeliveryGeneration {
    @Volatile
    var isActive: Boolean = true
        private set

    fun invalidate() {
        isActive = false
    }
}
