package leyline.match

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Exclusive execution domain for one match's session-side effects.
 *
 * Entrants submit work and wait for its result; only this single consumer runs
 * handlers. Reentrant calls from a handler execute inline so handler composition
 * cannot deadlock on its own queue.
 */
internal class MatchOwner(
    matchId: String,
    private val onTerminated: () -> Unit = {},
) {
    private val ownerThread = AtomicReference<Thread?>()
    private val closed = AtomicBoolean(false)
    private val executor =
        object :
            ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                LinkedBlockingQueue(),
                { action ->
                    Thread(action, "match-owner-${matchId.take(8)}").apply {
                        isDaemon = true
                        ownerThread.set(this)
                    }
                },
            ) {
            override fun terminated() {
                onTerminated()
            }
        }

    fun <T> reduce(action: () -> T): T {
        if (Thread.currentThread() === ownerThread.get()) return action()
        check(!closed.get()) { "Match owner is closed" }
        return try {
            executor.submit(Callable(action)).get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    fun enqueue(action: () -> Unit): Boolean {
        if (closed.get()) return false
        return try {
            executor.execute(action)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    fun assertOwnerThread() {
        check(Thread.currentThread() === ownerThread.get()) {
            "Match handler executed outside its owner"
        }
    }

    fun isOwnerThread(): Boolean = Thread.currentThread() === ownerThread.get()

    fun isClosed(): Boolean = closed.get()

    fun close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdown()
            while (true) {
                val pending = executor.queue.poll() ?: break
                if (pending is java.util.concurrent.Future<*>) pending.cancel(false)
            }
        }
    }

    fun awaitTermination() {
        if (isOwnerThread()) return
        check(executor.awaitTermination(30, TimeUnit.SECONDS)) {
            "Match owner did not terminate"
        }
    }
}
