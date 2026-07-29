package leyline.bridge.coord

import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Immutable terminal health fact from one engine-worker generation. */
sealed interface EngineWorkerExit {
    data object Completed : EngineWorkerExit

    data object Cancelled : EngineWorkerExit

    data class Failed(
        val failureType: String,
        val message: String?,
    ) : EngineWorkerExit
}

/** Truthful result of one cooperative worker-stop request. */
enum class EngineWorkerStop {
    NotRunning,
    Stopped,
    TimedOut,
}

/**
 * Operational owner of one in-process engine thread.
 *
 * Cancellation is cooperative: pending waits are cancelled, the thread is
 * interrupted, and the caller waits for a bounded join. A timed-out worker is
 * left tracked and may exit later; no hard-stop or recovery claim is made.
 */
internal class EngineWorkerSupervisor(
    private val joinTimeoutMs: Long = DEFAULT_JOIN_TIMEOUT_MS,
    private val onExit: (EngineWorkerExit) -> Unit,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val stopRequested = AtomicBoolean(false)
    private val worker = AtomicReference<Thread?>()
    private val started = CountDownLatch(1)

    val isRunning: Boolean get() = worker.get()?.isAlive == true

    fun start(
        name: String,
        block: () -> Unit,
    ): Thread {
        val thread =
            Thread(
                {
                    started.countDown()
                    val exit =
                        try {
                            if (!stopRequested.get()) block()
                            if (stopRequested.get()) EngineWorkerExit.Cancelled else EngineWorkerExit.Completed
                        } catch (failure: Throwable) {
                            if (stopRequested.get()) {
                                EngineWorkerExit.Cancelled
                            } else {
                                log.error("Engine worker failed", failure)
                                EngineWorkerExit.Failed(
                                    failureType = failure.javaClass.name,
                                    message = failure.message,
                                )
                            }
                        }
                    try {
                        onExit(exit)
                    } catch (failure: Throwable) {
                        log.error("Engine worker exit observer failed", failure)
                    } finally {
                        worker.compareAndSet(Thread.currentThread(), null)
                    }
                },
                name,
            ).apply {
                isDaemon = true
            }
        check(worker.compareAndSet(null, thread)) { "Engine worker thread already installed" }
        thread.start()
        return thread
    }

    fun stop(cancelPending: () -> Unit): EngineWorkerStop {
        val thread = worker.get() ?: return EngineWorkerStop.NotRunning
        stopRequested.set(true)
        cancelPending()
        thread.interrupt()
        if (thread === Thread.currentThread()) return EngineWorkerStop.Stopped

        try {
            thread.join(joinTimeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return if (thread.isAlive) {
            EngineWorkerStop.TimedOut
        } else {
            worker.compareAndSet(thread, null)
            EngineWorkerStop.Stopped
        }
    }

    fun awaitStarted(timeoutMs: Long): Boolean = started.await(timeoutMs, TimeUnit.MILLISECONDS)

    private companion object {
        const val DEFAULT_JOIN_TIMEOUT_MS = 2_000L
    }
}
