package forge.nexus.game

import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-owner protocol counter for GRE message sequencing.
 *
 * Replaces the dual-counter pattern where SessionOps and NexusGamePlayback each
 * maintained independent copies synced bidirectionally via `seedCounters()`/`getCounters()`.
 * That pattern required 17 seed callsites, 4 sync points, and a `max()` hack to prevent
 * stale values from clobbering advanced counters — and still had race windows.
 *
 * With a single shared [MessageCounter], both the session thread and engine thread
 * call [nextMsgId]/[nextGsId] on the same atomic. No seeding, no syncing, no races.
 *
 * Thread safety: all operations are atomic. The session thread (Netty I/O) and engine
 * thread (game daemon) may call [nextMsgId]/[nextGsId] concurrently — AtomicInteger
 * guarantees no duplicates.
 *
 * @param initialGsId starting gameStateId (handshake sets this before game start)
 * @param initialMsgId starting msgId (handshake sets this before game start)
 * @see <a href="../docs/decisions/003-message-counter.md">ADR-003</a>
 */
class MessageCounter(initialGsId: Int = 0, initialMsgId: Int = 1) {

    private val gsId = AtomicInteger(initialGsId)
    private val msgId = AtomicInteger(initialMsgId)

    /** Advance gsId and return the new value. */
    fun nextGsId(): Int = gsId.incrementAndGet()

    /** Advance msgId and return the new value. */
    fun nextMsgId(): Int = msgId.incrementAndGet()

    /** Current gsId (read-only snapshot, may be stale by the time you use it). */
    fun currentGsId(): Int = gsId.get()

    /** Current msgId (read-only snapshot, may be stale by the time you use it). */
    fun currentMsgId(): Int = msgId.get()

    /**
     * Set gsId to a specific value. Used during handshake setup where the
     * session advances gsId via [nextGameStateId] before the counter is shared.
     */
    fun setGsId(value: Int) { gsId.set(value) }

    /**
     * Set msgId to a specific value. Used during handshake setup.
     */
    fun setMsgId(value: Int) { msgId.set(value) }

    override fun toString(): String = "MessageCounter(gsId=${gsId.get()}, msgId=${msgId.get()})"
}
