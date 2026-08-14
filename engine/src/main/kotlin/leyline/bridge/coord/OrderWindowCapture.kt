package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.OrderCandidateValue
import leyline.bridge.handoff.OrderMoveIntent
import leyline.bridge.handoff.OrderMoveValue
import leyline.bridge.handoff.OrderWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId

/** Engine-thread capture for one immutable ordered-card window. */
internal object OrderWindowCapture {
    data class Initial(
        val value: OrderWindowValue,
        val handlesByOption: Map<Int, Card>,
    )

    fun initial(
        request: PromptRequest,
        candidateHandles: List<Card>,
        move: OrderMoveIntent?,
    ): Initial {
        val route = request.route as? ResolvedPromptRoute.Order ?: error("Order route required")
        check(candidateHandles.size == request.options.size) { "Order handles must match options" }
        val refsByOption = request.candidateRefs.associateBy { it.index }
        check(refsByOption.size == candidateHandles.size) { "Order candidates must have distinct option indices" }
        val candidates =
            candidateHandles.mapIndexed { index, handle ->
                val ref = refsByOption[index] ?: error("Missing Order candidate for option $index")
                check(ref.isCard() && ref.entityId == handle.id) { "Order candidate does not match its exact handle" }
                OrderCandidateValue(index, ForgeCardId(handle.id))
            }
        val cardIds = candidates.map { it.forgeCardId }
        check(cardIds.size == cardIds.distinct().size) { "Order candidates must be distinct cards" }
        move?.let { check(it.forgeCardIds == cardIds) { "Order move must match the exact candidate sequence" } }
        return Initial(
            value =
                OrderWindowValue(
                    kind = route.kind,
                    candidates = candidates.toList(),
                    sourceForgeCardId = request.sourceEntityId?.let(::ForgeCardId),
                    defaultOptionIndex = request.defaultIndex,
                    move =
                        move?.let {
                            OrderMoveValue(it.seatId, it.forgeCardIds.toList(), it.putOnTop)
                        },
                ),
            handlesByOption = candidateHandles.mapIndexed { index, card -> index to card }.toMap(),
        )
    }
}
