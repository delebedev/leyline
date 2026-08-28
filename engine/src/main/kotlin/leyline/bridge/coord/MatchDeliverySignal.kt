package leyline.bridge.coord

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Dedicated feed notification consumed by the transport delivery observer. */
internal class MatchDeliverySignal {
    private val semaphore = Semaphore(0)

    fun signal() {
        semaphore.release()
    }

    fun await(timeoutMs: Long): Boolean {
        val signaled = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
        if (signaled) semaphore.drainPermits()
        return signaled
    }
}
