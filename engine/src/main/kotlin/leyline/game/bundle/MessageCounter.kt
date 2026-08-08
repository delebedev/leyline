package leyline.game.bundle

import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared atomic counter for GRE gsId/msgId sequencing (ADR-003).
 *
 * One instance is created at session setup and passed to both [MatchSession]
 * and [leyline.game.state.GameBridge] at construction time. The session thread (Netty I/O) and
 * the engine thread (game daemon) both call [nextMsgId]/[nextGsId] on the
 * same atomics — monotonically increasing, no duplicates, no runtime sync.
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
        val lastPromptGsId: Int,
        val lastPromptMsgId: Int,
        val lastGameStateGsId: Int,
    )

    private val gsId = AtomicInteger(initialGsId)
    private val msgId = AtomicInteger(initialMsgId)
    private val lastPromptGsId = AtomicInteger(0)
    private val lastPromptMsgId = AtomicInteger(0)
    private val lastGameStateGsId = AtomicInteger(0)

    /** Advance gsId and return the new value. */
    fun nextGsId(): Int = gsId.incrementAndGet()

    /**
     * Allocate the next GameStateMessage id with the best known predecessor.
     *
     * Most callers need both values together: the new GSM's `gameStateId` and
     * its `prevGameStateId`. Prefer the last emitted GSM as the predecessor,
     * then fall back to the counter's current value for early setup paths. The
     * returned predecessor is always lower than the new id, preserving the hard
     * no-self-reference contract.
     */
    fun nextGameStateLink(): GameStateLink {
        val next = nextGsId()
        val previous =
            lastGameStateGsId
                .get()
                .takeIf { it in 1 until next }
                ?: (next - 1).coerceAtLeast(0)
        return GameStateLink(gsId = next, prevGsId = previous)
    }

    /** Advance msgId and return the new value. */
    fun nextMsgId(): Int = msgId.incrementAndGet()

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
     * gsId of the most recent message that asked the client for a response —
     * AAR, SelectTargetsReq, SelectNReq, GroupReq, etc. The client reflects
     * this gsId on every response it sends; staleness checks compare the
     * inbound gsId against this horizon to decide whether the response
     * targets a prompt that has since been replaced.
     *
     * Returns 0 when no prompt has been emitted yet — the same value a real
     * client sends pre-handshake, so the staleness predicate naturally
     * accepts 0 by short-circuiting on `clientGsId != 0`.
     *
     * **Why this and not [currentGsId] minus an offset?** Trailing post-content
     * echo GSMs advance [currentGsId] one past the prompt they follow, so
     * `clientGsId < currentGsId() - 1` was the working predicate — but that
     * ordinal offset breaks the moment echo policy diversifies (a second
     * echo, a skipped echo, two prompts back-to-back). Tracking the prompt
     * horizon directly keeps the predicate stable across echo-policy
     * changes. See [leyline.match.ActionPerformer] and
     * [leyline.match.CombatHandler] for the staleness sites.
     */
    fun lastPromptGsId(): Int = lastPromptGsId.get()

    /** msgId that a response to the most recent prompt must echo in `respId`. */
    fun lastPromptMsgId(): Int = lastPromptMsgId.get()

    /**
     * Record [gsId] as the gsId of a freshly-emitted prompt-bearing GRE.
     * Auto-called from `makeGRE` for prompt types in [PROMPT_GRE_TYPES];
     * callers shouldn't need to invoke this directly.
     *
     * Monotonic max via CAS — out-of-order callers never regress the
     * horizon. Concurrency: same [AtomicInteger] discipline as
     * [nextGsId]/[nextMsgId]; both session and engine threads write here.
     */
    fun markPromptGsId(gsId: Int) {
        markMonotonic(lastPromptGsId, gsId)
    }

    /** Record the msgId of a freshly emitted prompt-bearing GRE. */
    fun markPromptMsgId(msgId: Int) {
        markMonotonic(lastPromptMsgId, msgId)
    }

    /**
     * Record [gsId] as the latest GameStateMessage-bearing GRE seen by the client.
     * Monotonic for the same concurrency reason as [markPromptGsId].
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
        gsId.set(value)
    }

    /**
     * Set msgId to a specific value. Used during handshake setup.
     */
    fun setMsgId(value: Int) {
        msgId.set(value)
    }

    fun snapshot(): Snapshot =
        Snapshot(
            currentGsId = gsId.get(),
            currentMsgId = msgId.get(),
            lastPromptGsId = lastPromptGsId.get(),
            lastPromptMsgId = lastPromptMsgId.get(),
            lastGameStateGsId = lastGameStateGsId.get(),
        )

    override fun toString(): String =
        snapshot().let {
            "MessageCounter(gsId=${it.currentGsId}, msgId=${it.currentMsgId}, " +
                "lastPromptGsId=${it.lastPromptGsId}, lastPromptMsgId=${it.lastPromptMsgId}, " +
                "lastGameStateGsId=${it.lastGameStateGsId})"
        }
}
