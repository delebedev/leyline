package leyline.bridge.handoff

import forge.game.card.Card

/** Blocking engine-thread shell contract for the seven non-iterative PayCosts routes. */
interface OneShotPayCostsRuntime {
    fun awaitPayment(
        request: PromptRequest,
        candidateHandles: List<Card>,
        timeoutMs: Long?,
    ): OneShotPayCostsResult
}

/** Exact original option handles selected by a completed one-shot PayCosts window. */
data class OneShotPayCostsResult(
    val optionIndices: List<Int>,
    val handles: List<Card>,
) : List<Int> by optionIndices
