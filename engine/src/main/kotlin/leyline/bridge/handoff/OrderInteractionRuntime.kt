package leyline.bridge.handoff

import forge.game.card.Card

data class OrderInteractionResult(
    val optionIndices: List<Int>,
    val handles: List<Card>,
) : List<Int> by optionIndices

class OrderInteractionTimeoutException : RuntimeException("Order interaction timed out")

/** Blocking engine-thread shell contract for ordered-card prompts. */
interface OrderInteractionRuntime {
    fun awaitOrder(
        request: PromptRequest,
        candidateHandles: List<Card>,
        move: OrderMoveIntent?,
        timeoutMs: Long?,
    ): OrderInteractionResult
}
