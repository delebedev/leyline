package leyline.bridge.handoff

import forge.game.GameEntity

/** Exact SelectTargets-compatible entity choice owned by the match cut. */
interface CompatibilityCostSelectionRuntime {
    fun awaitSelection(
        request: PromptRequest,
        candidateHandles: List<GameEntity>,
        timeoutMs: Long?,
    ): CompatibilityCostSelectionResult
}

data class CompatibilityCostSelectionResult(
    val optionIndices: List<Int>,
    val handles: List<GameEntity>,
    val timedOut: Boolean = false,
)
