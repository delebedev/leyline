package leyline.bridge.handoff

import forge.game.card.Card

/** Exact SelectTargets-compatible residual card choice owned by the match cut. */
interface CompatibilityCostSelectionRuntime {
    fun awaitSelection(
        request: PromptRequest,
        candidateHandles: List<Card>,
        timeoutMs: Long?,
    ): CompatibilityCostSelectionResult
}

data class CompatibilityCostSelectionResult(
    val optionIndices: List<Int>,
    val handles: List<Card>,
    val timedOut: Boolean = false,
)
