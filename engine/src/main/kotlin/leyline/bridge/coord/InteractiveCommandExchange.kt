package leyline.bridge.coord

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One published re-prompt awaiting client acknowledgement. */
internal data class CommandDelivery(
    val token: Long,
    val acknowledged: CompletableFuture<Unit> = CompletableFuture(),
    val released: CompletableFuture<Unit> = CompletableFuture(),
)

/** Block the engine until the client confirms it received the re-prompt. */
internal fun CommandDelivery.awaitAcknowledgement() = unwrapCommandFailure { acknowledged.get() }

/** Block the acknowledging thread until the engine retires the command. */
internal fun CommandDelivery.awaitRelease() = unwrapCommandFailure { released.get() }

private inline fun <T> unwrapCommandFailure(body: () -> T): T =
    try {
        body()
    } catch (ex: ExecutionException) {
        throw ex.cause ?: ex
    }

/**
 * Cross-thread command handshake for one iterative prompt window.
 *
 * Session threads admit a correlated command and block on its reply; the engine
 * thread takes commands until the window deadline passes. The exchange owns
 * queue admission, the single in-flight slot, deadline polling, the delivery
 * token, reply exception unwrapping, and terminal wake-up.
 *
 * It knows nothing about protocol messages, target legality, mana shards,
 * selected options, prompt kinds, or completion defaults. The owning runtime
 * decides whether a command completes, republishes, or returns a fallback, and
 * holds the coordinator feed lock across every `Locked` member.
 */
internal class InteractiveCommandExchange<C : Any, R : Any>(
    private val deadlineNanos: Long?,
    private val nextDeliveryToken: () -> Long,
    private val replyOf: (C) -> CompletableFuture<R>,
) {
    private val commands = LinkedBlockingQueue<C>()

    /** The command the engine is currently answering, or null while idle. */
    var inFlight: C? = null
        private set

    /** The re-prompt awaiting client acknowledgement, or null while idle. */
    var delivery: CommandDelivery? = null
        private set

    /** Accept one correlated command for the engine to answer. */
    fun admitLocked(command: C) {
        inFlight = command
        commands.offer(command)
    }

    /** Block the submitting thread until the engine answers [command]. */
    fun awaitReply(command: C): R = unwrapCommandFailure { replyOf(command).get() }

    /**
     * Next command for the engine. When the window deadline passes without one,
     * [onDeadline] arbitrates: it either claims the timeout or returns a command
     * that arrived while the claim was being taken.
     */
    fun next(onDeadline: () -> C): C {
        val deadline = deadlineNanos ?: return commands.take()
        val remaining = deadline - System.nanoTime()
        val command = if (remaining <= 0) null else commands.poll(remaining, TimeUnit.NANOSECONDS)
        return command ?: onDeadline()
    }

    /** True when a command arrived after the deadline but before the claim. */
    fun queuedLocked(): Boolean = commands.isNotEmpty()

    fun pollQueuedLocked(): C? = commands.poll()

    /** Block until a late-arriving command is available. */
    fun takeQueued(): C = commands.take()

    fun beginDeliveryLocked(): CommandDelivery = CommandDelivery(nextDeliveryToken()).also { delivery = it }

    /** Acknowledge exactly [token]; a mismatched or absent delivery answers null. */
    fun acknowledgeLocked(token: Long): CommandDelivery? = delivery?.takeIf { it.token == token }?.also { it.acknowledged.complete(Unit) }

    /** Retire the finished command so the window accepts the next one. */
    fun clearDeliveryLocked() {
        delivery = null
        inFlight = null
    }

    /** Fail every waiter and hand the engine [terminal] so it stops taking commands. */
    fun terminateLocked(
        cause: Throwable,
        terminal: C,
    ) {
        delivery?.acknowledged?.completeExceptionally(cause)
        delivery?.released?.completeExceptionally(cause)
        inFlight?.let { replyOf(it).completeExceptionally(cause) }
        commands.forEach { replyOf(it).completeExceptionally(cause) }
        commands.offer(terminal)
    }
}
