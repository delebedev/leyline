package leyline.bridge.handoff

class StaticChoiceInteractionTimeoutException : RuntimeException("Static-choice interaction timed out")

/** The player cancelled a static choice that allowed it. */
class StaticChoiceCancelledException : RuntimeException(null, null, false, false)

/** Blocking engine-thread shell contract for static enum SelectN prompts. */
interface StaticChoiceInteractionRuntime {
    /** Indices of [request]'s options the published request would let the player choose. */
    fun shownOptions(request: PromptRequest): List<Int>

    fun awaitSelection(
        request: PromptRequest,
        timeoutMs: Long?,
    ): List<Int>
}
