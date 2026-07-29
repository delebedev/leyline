package leyline.game.bundle

import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared counter for GRE gsId/msgId sequencing (ADR-003).
 *
 * Match-play allocation belongs to the serial match owner for both interactive
 * and spectator paths. Frame compilation holds the allocation lock from fork
 * through commit so standalone owner builders cannot invalidate a prepared
 * allocation.
 *
 * The client requires gsIds to increase monotonically across the interleaved
 * message stream. A single shared counter is the correct coordination
 * primitive — partitioned ranges would break ordering.
 *
 * @param initialGsId starting gameStateId (handshake advances before game start)
 * @param initialMsgId starting msgId (handshake advances before game start)
 */
class MessageCounter(
    initialGsId: Int = 0,
    initialMsgId: Int = 1,
) {
    data class GameStateLink(
        val gsId: Int,
        val prevGsId: Int,
    )

    data class Snapshot(
        val currentGsId: Int,
        val currentMsgId: Int,
        val lastGameStateGsId: Int,
    )

    private val gsId = AtomicInteger(initialGsId)
    private val msgId = AtomicInteger(initialMsgId)
    private val lastGameStateGsId = AtomicInteger(0)

    /** Advance gsId and return the new value. */
    fun nextGsId(): Int = synchronized(this) { gsId.incrementAndGet() }

    /**
     * Allocate the next GameStateMessage id with the best known predecessor.
     *
     * Most callers need both values together: the new GSM's `gameStateId` and
     * its `prevGameStateId`. Prefer the last emitted GSM as the predecessor,
     * then fall back to the counter's current value for early setup paths. The
     * returned predecessor is always lower than the new id, preserving the hard
     * no-self-reference contract.
     */
    fun nextGameStateLink(): GameStateLink =
        synchronized(this) {
            val next = gsId.incrementAndGet()
            val previous =
                lastGameStateGsId
                    .get()
                    .takeIf { it in 1 until next }
                    ?: (next - 1).coerceAtLeast(0)
            GameStateLink(gsId = next, prevGsId = previous)
        }

    /** Advance msgId and return the new value. */
    fun nextMsgId(): Int = synchronized(this) { msgId.incrementAndGet() }

    /** Current gsId (read-only snapshot, may be stale by the time you use it). */
    fun currentGsId(): Int = gsId.get()

    /** Current msgId (read-only snapshot, may be stale by the time you use it). */
    fun currentMsgId(): Int = msgId.get()

    /**
     * gsId of the most recent outgoing message that carried a GameStateMessage.
     * Unlike [currentGsId], this never points at prompt-only GREs.
     */
    fun lastGameStateGsId(): Int = lastGameStateGsId.get()

    /**
     * Record [gsId] as the latest GameStateMessage-bearing GRE seen by the client.
     * Monotonic because interactive and spectator owner reductions both publish progress.
     */
    fun markGameStateGsId(gsId: Int) {
        markMonotonic(lastGameStateGsId, gsId)
    }

    private fun markMonotonic(
        value: AtomicInteger,
        gsId: Int,
    ) {
        while (true) {
            val cur = value.get()
            if (gsId <= cur) return
            if (value.compareAndSet(cur, gsId)) return
        }
    }

    /**
     * Set gsId to a specific value. Used during handshake setup where the
     * session advances gsId via [nextGameStateId] before the counter is shared.
     */
    fun setGsId(value: Int) {
        synchronized(this) {
            gsId.set(value)
        }
    }

    /**
     * Set msgId to a specific value. Used during handshake setup.
     */
    fun setMsgId(value: Int) {
        synchronized(this) {
            msgId.set(value)
        }
    }

    fun snapshot(): Snapshot =
        synchronized(this) {
            Snapshot(
                currentGsId = gsId.get(),
                currentMsgId = msgId.get(),
                lastGameStateGsId = lastGameStateGsId.get(),
            )
        }

    /**
     * Serialize one frame's forked allocation and commit with standalone
     * builders that allocate directly from this counter.
     */
    internal fun <T> withAllocationLock(action: () -> T): T =
        synchronized(this) {
            action()
        }

    /**
     * Commit IDs allocated by a frame compiler that started from [expected].
     *
     * Prompt and game-state delivery horizons are sink-owned and therefore are
     * not copied back from the compiler fork.
     */
    internal fun commitAllocation(
        expected: Snapshot,
        next: Snapshot,
        commitFrame: () -> Unit,
    ) {
        synchronized(this) {
            check(currentGsId() == expected.currentGsId && currentMsgId() == expected.currentMsgId) {
                "Message counter changed during frame compilation"
            }
            commitFrame()
            setGsId(next.currentGsId)
            setMsgId(next.currentMsgId)
        }
    }

    override fun toString(): String =
        snapshot().let {
            "MessageCounter(gsId=${it.currentGsId}, msgId=${it.currentMsgId}, " +
                "lastGameStateGsId=${it.lastGameStateGsId})"
        }

    companion object {
        internal fun fork(snapshot: Snapshot): MessageCounter =
            MessageCounter(snapshot.currentGsId, snapshot.currentMsgId).also { fork ->
                fork.markGameStateGsId(snapshot.lastGameStateGsId)
            }
    }
}
