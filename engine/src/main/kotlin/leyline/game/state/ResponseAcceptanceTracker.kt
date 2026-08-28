package leyline.game.state

import java.util.concurrent.atomic.AtomicInteger

/** Shell-owned observation of accepted and handled client responses. */
class ResponseAcceptanceTracker {
    private val accepted = AtomicInteger(0)
    private val lastHandledPromptMsgId = AtomicInteger(0)

    fun responsesAccepted(): Int = accepted.get()

    fun markResponseAccepted(respId: Int) {
        accepted.incrementAndGet()
        markPromptHandled(respId)
    }

    fun markPromptHandled(msgId: Int) {
        lastHandledPromptMsgId.accumulateAndGet(msgId, ::maxOf)
    }

    fun hasOutstandingPrompt(lastPromptMsgId: Int): Boolean = lastPromptMsgId > lastHandledPromptMsgId.get()
}
