package leyline.bridge.handoff

import org.slf4j.LoggerFactory
import java.util.Locale

/** Bounded prompt-call history kept separate from the blocking bridge. */
internal class PromptHistory(
    private val capacity: Int,
) {
    private val log = LoggerFactory.getLogger(PromptHistory::class.java)
    private val entries = ArrayDeque<PromptRecord>(capacity)

    val snapshot: List<PromptRecord>
        get() = synchronized(entries) { entries.toList() }

    fun record(
        request: PromptRequest,
        outcome: PromptCallStatus,
        result: List<Int>,
        elapsedMs: Long,
    ) {
        val frames =
            Thread
                .currentThread()
                .stackTrace
                .drop(4)
                .filter { it.className.startsWith("forge.") }
                .take(6)
                .map { "${it.className.substringAfterLast('.')}#${it.methodName}:${it.lineNumber}" }
        synchronized(entries) {
            if (entries.size >= capacity) entries.removeFirst()
            entries.addLast(
                PromptRecord(
                    promptType = request.promptType,
                    route = request.route,
                    message = request.message,
                    options = request.options,
                    min = request.min,
                    max = request.max,
                    candidateCount = request.candidateRefs.size,
                    outcome = outcome,
                    result = result,
                    callerFrames = frames,
                    costSelectionWeights = request.costSelectionWeights,
                    minSelectionWeight = request.minSelectionWeight,
                ),
            )
        }
        val secs = "%.1f".format(Locale.ROOT, elapsedMs / 1000.0)
        val msg = "Prompt [${request.promptType}] \"${request.message}\" → $outcome $result (${secs}s)"
        when (outcome) {
            PromptCallStatus.RESPONDED,
            PromptCallStatus.DEFAULTED_POLICY,
            -> log.info(msg)
            PromptCallStatus.TIMEOUT,
            PromptCallStatus.ERROR,
            PromptCallStatus.ALREADY_PENDING,
            PromptCallStatus.NON_GAME_THREAD,
            PromptCallStatus.NON_INTERACTIVE_SCOPE,
            -> log.warn(msg)
        }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}
