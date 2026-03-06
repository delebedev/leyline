package leyline.bridge

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Shared signal between [GameActionBridge], [InteractivePromptBridge], and an
 * external observer (e.g. [GameBridge.awaitPriorityWithTimeout]).
 *
 * Bridges call [signal] when they post a pending item (action or prompt).
 * The observer calls [awaitSignal] instead of polling with Thread.sleep.
 *
 * Uses a [Semaphore] so permits accumulate — if a bridge signals before the
 * observer starts waiting, the permit is still available (no lost signals).
 */
class PrioritySignal {
    private val semaphore = Semaphore(0)

    /** Notify that a bridge has a pending item. Safe to call multiple times. */
    fun signal() {
        semaphore.release()
    }

    /**
     * Wait for a signal or timeout. Returns true if signaled, false on timeout.
     * Drains extra permits so they don't accumulate unboundedly.
     */
    fun awaitSignal(timeoutMs: Long): Boolean {
        val got = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
        if (got) semaphore.drainPermits()
        return got
    }
}
