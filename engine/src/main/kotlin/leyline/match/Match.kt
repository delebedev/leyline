package leyline.match

import leyline.bridge.coord.EngineWorkerExit
import leyline.bridge.coord.EngineWorkerStop
import leyline.game.state.GameBridge
import java.util.concurrent.atomic.AtomicReference

/** Lifecycle states for a match. */
enum class MatchState { WAITING, RUNNING, FINISHED }

/**
 * Owns the full lifecycle of a single game match.
 * Tracks state (WAITING → RUNNING → FINISHED) and provides deterministic teardown via [close].
 */
class Match(
    val matchId: String,
    val bridge: GameBridge,
) {
    private val stateRef = AtomicReference(MatchState.WAITING)
    private val workerSupervisor = MatchWorkerSupervisor(bridge)

    /** Current lifecycle state. */
    val state: MatchState get() = stateRef.get()

    /** Most recent truthful result from cooperative worker stop. */
    val workerStop: EngineWorkerStop get() = workerSupervisor.stopOutcome()

    /** Optional callback fired on every state transition. Volatile for JMM visibility. */
    @Volatile var onStateChanged: ((MatchState) -> Unit)? = null

    @Synchronized
    fun start(
        seed: Long? = null,
        deckList: String? = null,
        deckList1: String? = null,
        deckList2: String? = null,
        variant: String? = null,
    ) {
        if (stateRef.get() != MatchState.WAITING) return
        val started = workerSupervisor.start(seed, deckList, deckList1, deckList2, variant)
        if (started && stateRef.compareAndSet(MatchState.WAITING, MatchState.RUNNING)) {
            onStateChanged?.invoke(MatchState.RUNNING)
        }
    }

    @Synchronized
    fun startAiVsAi(
        seed: Long? = null,
        deckList: String? = null,
        deckList1: String? = null,
        deckList2: String? = null,
        variant: String? = null,
        startGameHook: Runnable? = null,
    ) {
        if (stateRef.get() != MatchState.WAITING) return
        val started = workerSupervisor.startAiVsAi(seed, deckList, deckList1, deckList2, variant, startGameHook)
        if (started && stateRef.compareAndSet(MatchState.WAITING, MatchState.RUNNING)) {
            onStateChanged?.invoke(MatchState.RUNNING)
        }
    }

    @Synchronized
    fun startPuzzle(path: String) {
        if (stateRef.get() != MatchState.WAITING) return
        val started = workerSupervisor.startPuzzle(path)
        if (started && stateRef.compareAndSet(MatchState.WAITING, MatchState.RUNNING)) {
            onStateChanged?.invoke(MatchState.RUNNING)
        }
    }

    internal fun bindWorkerFailure(handler: (EngineWorkerExit.Failed) -> Unit) {
        workerSupervisor.bindFailureHandler(handler)
    }

    /** Semantic terminal transition invoked as the owner's final action. */
    fun finish() {
        val previous = stateRef.getAndSet(MatchState.FINISHED)
        if (previous != MatchState.FINISHED) {
            onStateChanged?.invoke(MatchState.FINISHED)
        }
    }

    /** Operational cancellation after the owner has chosen a terminal state. */
    fun stop(): EngineWorkerStop = workerSupervisor.stop()

    /**
     * Idempotent teardown: transitions to FINISHED, deterministically tears down
     * heavyweight resources (EventBus, game loop), then clears per-seat bridge state.
     * Safe to call from any thread, multiple times.
     */
    fun close() {
        finish()
        stop()
    }
}
