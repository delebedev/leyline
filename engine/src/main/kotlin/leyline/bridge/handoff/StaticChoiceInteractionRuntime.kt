package leyline.bridge.handoff

class StaticChoiceInteractionTimeoutException : RuntimeException("Static-choice interaction timed out")

/** Blocking engine-thread shell contract for static enum SelectN prompts. */
interface StaticChoiceInteractionRuntime {
    fun awaitSelection(
        request: PromptRequest,
        timeoutMs: Long?,
    ): List<Int>
}
