package leyline.match

import leyline.game.GameBridge
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

    /** Current lifecycle state. */
    val state: MatchState get() = stateRef.get()

    /** Optional callback fired on every state transition. */
    var onStateChanged: ((MatchState) -> Unit)? = null

    fun start(
        seed: Long? = null,
        deckList: String? = null,
        deckList1: String? = null,
        deckList2: String? = null,
    ) {
        if (stateRef.compareAndSet(MatchState.WAITING, MatchState.RUNNING)) {
            onStateChanged?.invoke(MatchState.RUNNING)
        }
        bridge.start(seed, deckList, deckList1, deckList2)
    }

    /**
     * Idempotent teardown: transitions to FINISHED, shuts down the bridge, fires callback.
     * Safe to call from any thread, multiple times.
     */
    fun close() {
        val prev = stateRef.getAndSet(MatchState.FINISHED)
        if (prev == MatchState.FINISHED) return // already closed
        bridge.shutdown()
        onStateChanged?.invoke(MatchState.FINISHED)
    }

    /** @deprecated Use [close] instead. */
    @Deprecated("Use close() for deterministic lifecycle", replaceWith = ReplaceWith("close()"))
    fun shutdown() = close()
}
