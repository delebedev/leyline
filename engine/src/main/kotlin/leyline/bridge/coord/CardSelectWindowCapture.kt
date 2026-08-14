package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import leyline.bridge.handoff.CardSelectCandidateValue
import leyline.bridge.handoff.CardSelectKind
import leyline.bridge.handoff.CardSelectWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId

/** Engine-thread capture for one immutable card-backed SelectN window. */
internal object CardSelectWindowCapture {
    data class Initial(
        val value: CardSelectWindowValue,
        val handlesByOption: Map<Int, Card>,
    )

    fun initial(
        request: PromptRequest,
        candidateHandles: List<Card>,
    ): Initial {
        val route = request.route as? ResolvedPromptRoute.CardSelect ?: error("CardSelect route required")
        check(candidateHandles.size == request.options.size) { "CardSelect handles must match options" }
        val refsByOption = request.candidateRefs.associateBy { it.index }
        check(refsByOption.size == candidateHandles.size) { "CardSelect candidates must have distinct option indices" }
        val candidates =
            candidateHandles.mapIndexed { index, handle ->
                val ref = refsByOption[index] ?: error("Missing CardSelect candidate for option $index")
                check(ref.isCard() && ref.entityId == handle.id) { "CardSelect candidate does not match its exact handle" }
                CardSelectCandidateValue(index, ForgeCardId(handle.id))
            }
        check(candidates.map { it.forgeCardId }.distinct().size == candidates.size) {
            "CardSelect candidates must be distinct cards"
        }
        check(request.min in 0..request.max && request.max <= candidates.size) { "Invalid CardSelect cardinality" }
        check(request.defaultIndex in candidates.indices) { "Invalid CardSelect default option" }
        if (route.descriptor.kind == CardSelectKind.LegendRule) {
            check(candidates.size >= 2) { "Legend Rule requires multiple candidates" }
            check(request.min == 1 && request.max == 1) { "Legend Rule requires exactly one selection" }
            check(request.defaultIndex == 0) { "Legend Rule default must keep the first candidate" }
            check(request.sourceEntityId == null) { "Legend Rule has no card source" }
        }
        if (route.descriptor.kind == CardSelectKind.LibraryPutback) {
            check(request.sourceEntityId != null) { "Library putback requires a card source" }
            check(candidateHandles.all { it.isInZone(ZoneType.Hand) }) { "Library putback candidates must be in hand" }
        }
        return Initial(
            value =
                CardSelectWindowValue(
                    kind = route.descriptor.kind,
                    candidates = candidates,
                    sourceForgeCardId = request.sourceEntityId?.let(::ForgeCardId),
                    min = request.min,
                    max = request.max,
                    defaultOptionIndex = request.defaultIndex,
                    choiceResultSentiment = route.descriptor.choiceResultSentiment,
                ),
            handlesByOption = candidateHandles.mapIndexed { index, card -> index to card }.toMap(),
        )
    }
}
