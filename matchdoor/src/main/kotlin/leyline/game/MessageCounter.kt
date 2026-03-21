package leyline.game

import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared atomic counter for GRE gsId/msgId sequencing (ADR-003).
 *
 * One instance is created at session setup and passed to both [MatchSession]
 * and [GameBridge] at construction time. The session thread (Netty I/O) and
 * the engine thread (game daemon) both call [nextMsgId]/[nextGsId] on the
 * same atomics — monotonically increasing, no duplicates, no runtime sync.
 *
 * The client requires gsIds to increase monotonically across the interleaved
 * message stream. A single shared counter is the correct coordination
 * primitive — partitioned ranges would break ordering.
 *
 * @param initialGsId starting gameStateId (handshake advances before game start)
 * @param initialMsgId starting msgId (handshake advances before game start)
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
    fun setGsId(value: Int) {
        gsId.set(value)
    }

    /**
     * Set msgId to a specific value. Used during handshake setup.
     */
    fun setMsgId(value: Int) {
        msgId.set(value)
    }

    override fun toString(): String = "MessageCounter(gsId=${gsId.get()}, msgId=${msgId.get()})"
}
