package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.GroupingCandidateValue
import leyline.bridge.handoff.GroupingWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext

/** Engine-thread capture for one immutable Scry or Surveil grouping window. */
internal object GroupingWindowCapture {
    data class Initial(
        val value: GroupingWindowValue,
        val handlesByOption: Map<Int, Card>,
    )

    fun initial(
        request: PromptRequest,
        candidateHandles: List<Card>,
    ): Initial {
        val route = request.route as? ResolvedPromptRoute.Grouping ?: error("Grouping route required")
        check(route.context == GroupingContext.Scry_a0f6 || route.context == GroupingContext.Surveil) {
            "Only Scry and Surveil Grouping routes are coordinator-owned"
        }
        check(candidateHandles.isNotEmpty()) { "Grouping requires at least one candidate" }
        val singleCardChoice = candidateHandles.size == 1
        if (singleCardChoice) {
            check(request.options.size == 2 && request.min == 1 && request.max == 1) {
                "Single-card Grouping requires one binary choice"
            }
        } else {
            check(request.options.size == candidateHandles.size && request.min == 0 && request.max == candidateHandles.size) {
                "Multi-card Grouping requires the complete candidate range"
            }
        }
        check(request.defaultIndex in request.options.indices) { "Grouping default must name an exact option" }
        check(candidateHandles.size == request.candidateRefs.size) { "Grouping handles must match candidates" }
        val refsByOption = request.candidateRefs.associateBy { it.index }
        check(refsByOption.size == candidateHandles.size) { "Grouping candidates must have distinct option indices" }
        val candidates =
            candidateHandles.mapIndexed { index, handle ->
                val ref = refsByOption[index] ?: error("Missing Grouping candidate for option $index")
                check(ref.isCard() && ref.entityId == handle.id) { "Grouping candidate does not match its exact handle" }
                GroupingCandidateValue(index, ForgeCardId(handle.id))
            }
        check(candidates.map { it.forgeCardId }.distinct().size == candidates.size) {
            "Grouping candidates must be distinct cards"
        }
        return Initial(
            value =
                GroupingWindowValue(
                    context = route.context,
                    candidates = candidates,
                    source = request.groupingSource,
                    defaultOptionIndex = request.defaultIndex,
                    singleCardChoice = singleCardChoice,
                ),
            handlesByOption = candidateHandles.mapIndexed { index, card -> index to card }.toMap(),
        )
    }
}
