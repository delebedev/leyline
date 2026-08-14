package leyline.bridge.handoff

import forge.game.card.Card

data class RevealChoiceInteractionResult(
    val optionIndices: List<Int>,
    val handles: List<Card>,
    val timedOut: Boolean,
) : List<Int> by optionIndices

/** Blocking engine-thread contract for reveal-backed SelectN prompts. */
interface RevealChoiceInteractionRuntime {
    fun awaitSelection(
        request: PromptRequest,
        candidateHandles: List<Card>,
        revealEntry: PromptJournal.RevealEntry,
        recordExiledUnderSource: Boolean,
        timeoutMs: Long?,
    ): RevealChoiceInteractionResult
}
