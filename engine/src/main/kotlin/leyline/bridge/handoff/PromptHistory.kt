package leyline.bridge.handoff

/** Bounded prompt-call history kept separate from the blocking bridge. */
internal class PromptHistory(
    private val capacity: Int,
) {
    private val entries = ArrayDeque<PromptRecord>(capacity)

    val snapshot: List<PromptRecord>
        get() = synchronized(entries) { entries.toList() }

    fun record(
        request: PromptRequest,
        outcome: PromptCallStatus,
        result: List<Int>,
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
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}
