package leyline.bridge.handoff

/**
 * Recent prompt calls, capped at [capacity].
 *
 * Policy defaults are also kept in [defaults] with their own cap, so a long
 * game's routine prompts cannot evict the decisions nobody was asked about.
 */
internal class PromptHistory(
    private val capacity: Int,
    private val defaultCapacity: Int = capacity * 10,
) {
    private val entries = ArrayDeque<PromptRecord>(capacity)
    private val defaultEntries = ArrayDeque<PromptRecord>()

    val snapshot: List<PromptRecord>
        get() = synchronized(entries) { entries.toList() }

    /** Every [PromptCallStatus.DEFAULTED_POLICY] record, oldest first, capped at [defaultCapacity]. */
    val defaults: List<PromptRecord>
        get() = synchronized(entries) { defaultEntries.toList() }

    /** Policy defaults evicted from [defaults] by its cap; nonzero means [defaults] is incomplete. */
    val droppedDefaults: Int
        get() = synchronized(entries) { droppedDefaultCount }

    private var droppedDefaultCount = 0

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
        val record =
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
            )
        synchronized(entries) {
            if (entries.size >= capacity) entries.removeFirst()
            entries.addLast(record)
            if (outcome == PromptCallStatus.DEFAULTED_POLICY) {
                if (defaultEntries.size >= defaultCapacity) {
                    defaultEntries.removeFirst()
                    droppedDefaultCount++
                }
                defaultEntries.addLast(record)
            }
        }
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
            defaultEntries.clear()
            droppedDefaultCount = 0
        }
    }
}
