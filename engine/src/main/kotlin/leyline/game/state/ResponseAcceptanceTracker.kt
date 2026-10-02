package leyline.game.state

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Shell-owned observation of accepted and handled client responses. */
class ResponseAcceptanceTracker {
    data class AcceptedResponse(
        val ordinal: Int,
        val respId: Int,
    )

    private val accepted = AtomicReference<List<AcceptedResponse>>(emptyList())
    private val lastHandledPromptMsgId = AtomicInteger(0)

    fun responsesAccepted(): Int = accepted.get().lastOrNull()?.ordinal ?: 0

    fun acceptedSnapshot(): List<AcceptedResponse> = accepted.get()

    fun markResponseAccepted(respId: Int) {
        // ponytail: retain 32 responses for short diagnostic probes; widen only for longer compound inputs.
        accepted.updateAndGet { prior -> (prior + AcceptedResponse((prior.lastOrNull()?.ordinal ?: 0) + 1, respId)).takeLast(32) }
        markPromptHandled(respId)
    }

    fun markPromptHandled(msgId: Int) {
        lastHandledPromptMsgId.accumulateAndGet(msgId, ::maxOf)
    }

    fun hasOutstandingPrompt(lastPromptMsgId: Int): Boolean = lastPromptMsgId > lastHandledPromptMsgId.get()
}
