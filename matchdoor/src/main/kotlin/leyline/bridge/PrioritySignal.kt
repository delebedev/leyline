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

    /**
     * Set after a prompt resolves so the next priority check skips smart-phase-skip
     * and lets the player see the updated board. Consumed by [consumePromptResolved].
     */
    @Volatile
    private var promptJustResolved: Boolean = false

    /** Marked after a prompt resolves so the next priority check skips smart-phase-skip. */
    fun markPromptResolved() {
        promptJustResolved = true
    }

    /** Single-consumer check-and-clear for the priority loop. */
    fun consumePromptResolved(): Boolean {
        if (!promptJustResolved) return false
        promptJustResolved = false
        return true
    }

    /** Notify that a waiter should re-check its exit conditions. */
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
