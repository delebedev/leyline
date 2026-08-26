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
    private val sessionProvider: () -> MatchSession?,
    private val seatId: SeatId,
) {
    private val log = LoggerFactory.getLogger(MatchRuntimeDeliveryObserver::class.java)

    @Volatile
    private var stopped = true
    private var worker: Thread? = null

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
        if (stopped) return
        stopped = true
        sessionProvider()
            ?.gameBridge
            ?.cutCoordinator
            ?.deliverySignal
            ?.signal()
        val current = worker
        if (current != null && current !== Thread.currentThread()) current.join(2_000)
        worker = null
    }

    private fun run() {
        while (!stopped) {
            val session = sessionProvider() ?: return
            val coordinator = session.gameBridge.cutCoordinator
            if (!session.connection.runtimeDeliveryReady) {
                coordinator.deliverySignal.await(1_000)
                continue
            }
            if (!coordinator.hasCommittedBatches(seatId)) {
                coordinator.deliverySignal.await(1_000)
                continue
            }
            try {
                session.deliverRuntimeHorizon()
            } catch (ex: Throwable) {
                log.warn("Runtime horizon delivery failed: {}", ex.message, ex)
                return
            }
        }
    }
}
