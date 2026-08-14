package leyline.bridge.handoff

import forge.game.card.Card

/** Blocking engine-thread shell contract for Convoke, Improvise, and Waterbend payment. */
interface ManaSourcePaymentRuntime {
    fun awaitPayment(
        request: PromptRequest,
        candidateHandles: List<Card>,
        timeoutMs: Long?,
    ): ManaSourcePaymentResult

    fun recordFinalPayment(value: FinalManaSourcePaymentValue)
}
