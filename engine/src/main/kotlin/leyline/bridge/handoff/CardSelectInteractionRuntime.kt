package leyline.bridge.handoff

import forge.game.card.Card

data class CardSelectInteractionResult(
    val optionIndices: List<Int>,
    val handles: List<Card>,
) : List<Int> by optionIndices

class CardSelectInteractionTimeoutException : RuntimeException("Card-select interaction timed out")

/** Blocking engine-thread shell contract for card-backed SelectN prompts. */
interface CardSelectInteractionRuntime {
    fun awaitSelection(
        request: PromptRequest,
        candidateHandles: List<Card>,
        timeoutMs: Long?,
    ): CardSelectInteractionResult
}
