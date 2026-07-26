package leyline.match

import leyline.game.bundle.PROMPT_GRE_TYPES
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
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
    private val protocolState = OwnerProtocolState()
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

    fun observeOutbound(messages: List<GREToClientMessage>) {
        assertOwnerThread()
        protocolState.observeOutbound(messages)
    }

    fun observeOutbound(message: MatchServiceToClientMessage) {
        assertOwnerThread()
        protocolState.observeOutbound(message)
    }

    fun lastPromptGsId(): Int {
        assertOwnerThread()
        return protocolState.lastPromptGsId
    }

    fun lastPromptMsgId(): Int {
        assertOwnerThread()
        return protocolState.lastPromptMsgId
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

    /**
     * Prompt correlation horizon advanced only by the serial match owner.
     *
     * The client echoes these IDs when answering the latest delivered prompt.
     * Tracking that prompt directly avoids deriving a fragile offset from the
     * allocation counter after trailing non-prompt messages advance it.
     */
    private class OwnerProtocolState {
        var lastPromptGsId: Int = 0
            private set

        var lastPromptMsgId: Int = 0
            private set

        fun observeOutbound(messages: List<GREToClientMessage>) {
            messages.forEach(::observeOutbound)
        }

        fun observeOutbound(message: MatchServiceToClientMessage) {
            if (message.hasGreToClientEvent()) {
                observeOutbound(message.greToClientEvent.greToClientMessagesList)
            }
        }

        private fun observeOutbound(message: GREToClientMessage) {
            if (message.type !in PROMPT_GRE_TYPES) return
            lastPromptGsId = maxOf(lastPromptGsId, message.gameStateId)
            lastPromptMsgId = maxOf(lastPromptMsgId, message.msgId)
        }
    }
}
