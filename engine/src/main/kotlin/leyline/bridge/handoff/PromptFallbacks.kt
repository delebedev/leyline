package leyline.bridge.handoff

import forge.game.card.Card
import leyline.bridge.types.ForgeCardId

/** Value adapters used only when a match-scoped runtime is intentionally inactive. */
internal fun fallbackOrder(
    indices: List<Int>,
    candidateHandles: List<Card>,
): OrderInteractionResult {
    val ordered = (indices.filter(candidateHandles.indices::contains) + candidateHandles.indices).distinct()
    return OrderInteractionResult(ordered, ordered.map(candidateHandles::get))
}

internal fun fallbackGrouping(
    request: PromptRequest,
    selectedIndices: List<Int>,
    candidateHandles: List<Card>,
): GroupingInteractionResult {
    val awayIndices =
        if (candidateHandles.size == 1 && request.options.size == 2) {
            if (selectedIndices.firstOrNull() == 1) listOf(0) else emptyList()
        } else {
            selectedIndices.filter(candidateHandles.indices::contains).distinct()
        }
    return GroupingInteractionResult(
        interactionId = "",
        context = (request.route as ResolvedPromptRoute.Grouping).context,
        topHandles = candidateHandles.filterIndexed { index, _ -> index !in awayIndices },
        awayHandles = candidateHandles.filterIndexed { index, _ -> index in awayIndices },
        timedOut = false,
    )
}

internal fun fallbackCardSelect(
    indices: List<Int>,
    candidateHandles: List<Card>,
): CardSelectInteractionResult {
    val selected = indices.filter(candidateHandles.indices::contains).distinct()
    return CardSelectInteractionResult(selected, selected.map(candidateHandles::get))
}

internal fun fallbackRevealChoice(
    indices: List<Int>,
    request: PromptRequest,
    candidateHandles: List<Card>,
    revealEntry: PromptJournal.RevealEntry,
    recordExiledUnderSource: Boolean,
    journal: PromptJournal,
): RevealChoiceInteractionResult {
    val selected = indices.filter(candidateHandles.indices::contains).distinct()
    if (recordExiledUnderSource) {
        request.sourceEntityId?.let(::ForgeCardId)?.let { source ->
            selected.forEach { index ->
                journal.record(PromptSideEffect.ExiledUnderSource(ForgeCardId(candidateHandles[index].id), source))
            }
        }
    }
    journal.clearActiveReveal(revealEntry)
    return RevealChoiceInteractionResult(selected, selected.map(candidateHandles::get), timedOut = false)
}

internal fun fallbackOneShot(
    indices: List<Int>,
    candidateHandles: List<Card>,
): OneShotPayCostsResult =
    OneShotPayCostsResult(
        indices,
        indices.mapNotNull(candidateHandles::getOrNull),
    )
