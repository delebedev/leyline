package leyline.match

import leyline.bridge.coord.EngineWorkerExit
import leyline.bridge.coord.EngineWorkerStop
import leyline.game.state.GameBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns one match's engine-worker lifecycle and publishes one terminal failure.
 *
 * The bridge may fail while its blocking start call is still inside a match
 * factory. The immutable failure is retained until the registry installs its
 * lifecycle handler.
 */
internal class MatchWorkerSupervisor(
    private val bridge: GameBridge,
    private val stopWorker: () -> EngineWorkerStop = bridge::shutdown,
    private val stopWaitTimeoutMs: Long = DEFAULT_STOP_WAIT_TIMEOUT_MS,
) {
    private enum class Lifecycle {
        Idle,
        Starting,
        Running,
        StopRequested,
        Stopped,
    }

    private val lifecycleLock = Any()
    private val exitListener: (EngineWorkerExit) -> Unit = ::onWorkerExit
    private val latestExit = AtomicReference<EngineWorkerExit?>()
    private val failureHandler = AtomicReference<((EngineWorkerExit.Failed) -> Unit)?>(null)
    private val failureDelivered = AtomicBoolean(false)
    private val stopCompleted = CountDownLatch(1)
    private var lifecycle = Lifecycle.Idle
    private var latestStop = EngineWorkerStop.NotRunning

    init {
        bridge.observeWorkerExit(exitListener)
    }

    fun bindFailureHandler(handler: (EngineWorkerExit.Failed) -> Unit) {
        check(failureHandler.compareAndSet(null, handler)) {
            "Engine worker failure handler already bound"
        }
        deliverFailure()
    }

    fun start(
        seed: Long?,
        deckList: String?,
        deckList1: String?,
        deckList2: String?,
        variant: String?,
    ): Boolean =
        startWorker { continueAfterWorkerReady ->
            bridge.start(seed, deckList, deckList1, deckList2, variant, continueAfterWorkerReady)
        }

    fun startAiVsAi(
        seed: Long?,
        deckList: String?,
        deckList1: String?,
        deckList2: String?,
        variant: String?,
        startGameHook: Runnable?,
    ): Boolean =
        startWorker { continueAfterWorkerReady ->
            bridge.startAiVsAi(
                seed,
                deckList,
                deckList1,
                deckList2,
                variant,
                startGameHook,
                continueAfterWorkerReady,
            )
        }

    fun startPuzzle(path: String): Boolean =
        startWorker { continueAfterWorkerReady ->
            bridge.startPuzzleFile(path, continueAfterWorkerReady)
        }

    fun stop(): EngineWorkerStop {
        val stopNow =
            synchronized(lifecycleLock) {
                when (lifecycle) {
                    Lifecycle.Idle -> {
                        lifecycle = Lifecycle.Stopped
                        latestStop = EngineWorkerStop.NotRunning
                        stopCompleted.countDown()
                        detach()
                        return EngineWorkerStop.NotRunning
                    }
                    Lifecycle.Starting -> {
                        lifecycle = Lifecycle.StopRequested
                        false
                    }
                    Lifecycle.Running -> {
                        lifecycle = Lifecycle.StopRequested
                        true
                    }
                    Lifecycle.StopRequested,
                    Lifecycle.Stopped,
                    -> false
                }
            }
        if (stopNow) {
            completeStop(stopWorker())
        }
        return awaitStopOutcome()
    }

    private fun awaitStopOutcome(): EngineWorkerStop {
        val completed =
            try {
                stopCompleted.await(stopWaitTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        return if (completed) {
            synchronized(lifecycleLock) { latestStop }
        } else {
            EngineWorkerStop.TimedOut
        }
    }

    fun stopOutcome(): EngineWorkerStop = synchronized(lifecycleLock) { latestStop }

    internal fun isStopRequested(): Boolean =
        synchronized(lifecycleLock) {
            lifecycle == Lifecycle.StopRequested
        }

    fun latestExit(): EngineWorkerExit? = latestExit.get()

    private fun onWorkerExit(exit: EngineWorkerExit) {
        latestExit.set(exit)
        deliverFailure()
        val terminal =
            synchronized(lifecycleLock) {
                lifecycle == Lifecycle.StopRequested || lifecycle == Lifecycle.Stopped
            }
        if (terminal) {
            detach()
        }
    }

    internal fun startWorker(start: ((() -> Boolean) -> Unit)): Boolean {
        synchronized(lifecycleLock) {
            if (lifecycle != Lifecycle.Idle) return false
            lifecycle = Lifecycle.Starting
        }
        try {
            start(::continueAfterWorkerReady)
        } catch (failure: Throwable) {
            completeStop(stopWorker())
            throw failure
        }

        return synchronized(lifecycleLock) { lifecycle == Lifecycle.Running }
    }

    private fun continueAfterWorkerReady(): Boolean {
        val stopNow =
            synchronized(lifecycleLock) {
                when (lifecycle) {
                    Lifecycle.Starting -> {
                        lifecycle = Lifecycle.Running
                        false
                    }
                    Lifecycle.StopRequested -> true
                    Lifecycle.Running -> false
                    Lifecycle.Idle,
                    Lifecycle.Stopped,
                    -> return false
                }
            }
        if (stopNow) {
            completeStop(stopWorker())
            return false
        }
        return true
    }

    private fun completeStop(result: EngineWorkerStop) {
        val publish =
            synchronized(lifecycleLock) {
                if (lifecycle == Lifecycle.Stopped) {
                    false
                } else {
                    lifecycle = Lifecycle.Stopped
                    latestStop = result
                    true
                }
            }
        if (!publish) return
        stopCompleted.countDown()
        if (result != EngineWorkerStop.TimedOut) {
            detach()
        }
    }

    private fun deliverFailure() {
        val failure = latestExit.get() as? EngineWorkerExit.Failed ?: return
        val handler = failureHandler.get() ?: return
        if (failureDelivered.compareAndSet(false, true)) {
            handler(failure)
        }
    }

    private fun detach() {
        bridge.stopObservingWorkerExit(exitListener)
        failureHandler.set(null)
    }

    private companion object {
        const val DEFAULT_STOP_WAIT_TIMEOUT_MS = 2_000L
    }
}
