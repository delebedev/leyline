package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.OneShotPayCostsCandidateValue
import leyline.bridge.handoff.OneShotPayCostsWindowValue
import leyline.bridge.handoff.PayCostsRouteKind
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId

/** Engine-thread freezing for the seven non-iterative PayCosts routes. */
internal class OneShotPayCostsWindowCapture(
    private val owner: MatchCutCoordinator,
) {
    data class Initial(
        val value: OneShotPayCostsWindowValue,
        val handlesByOption: Map<Int, Card>,
    )

    fun initial(
        request: PromptRequest,
        candidateHandles: List<Card>,
    ): Initial {
        val route = request.route as? ResolvedPromptRoute.PayCosts ?: error("One-shot payment requires a PayCosts route")
        require(route.descriptor.manaSourcePayment == null) { "Iterative mana-source payment cannot use one-shot runtime" }
        val handlesById = candidateHandles.associateBy { it.id }
        require(handlesById.size == candidateHandles.size) { "One-shot PayCosts candidates require unique Forge ids" }
        val handlesByOption =
            request.candidateRefs.associate { ref ->
                ref.index to (handlesById[ref.entityId] ?: error("Missing PayCosts candidate ${ref.entityId}"))
            }
        require(handlesByOption.size == request.candidateRefs.size) { "PayCosts option indices must be unique" }
        val weights = selectionWeights(route.descriptor.kind, request)
        val candidates =
            request.candidateRefs.mapIndexed { index, ref ->
                OneShotPayCostsCandidateValue(
                    originalOptionIndex = ref.index,
                    forgeCardId = ForgeCardId(ref.entityId),
                    weight = weights[index],
                )
            }
        val (minSelections, maxSelections) = selectionRange(route.descriptor.kind, request)
        val sourceForgeCardId = request.sourceEntityId?.let(::ForgeCardId)
        val sourceInstanceId =
            sourceForgeCardId?.let {
                owner.bridge
                    .projectionStateSnapshot()
                    .identities.forgeIdToInstanceId[it]
                    ?.value
            }
        return Initial(
            value =
                OneShotPayCostsWindowValue(
                    kind = route.descriptor.kind,
                    candidates = candidates,
                    sourceForgeCardId = sourceForgeCardId,
                    sourceInstanceId = sourceInstanceId,
                    minSelections = minSelections,
                    maxSelections = maxSelections,
                    minimumWeight = request.minSelectionWeight,
                    defaultOptionIndex = request.defaultIndex,
                ),
            handlesByOption = handlesByOption,
        )
    }

    private fun selectionWeights(
        kind: PayCostsRouteKind,
        request: PromptRequest,
    ): List<Int> =
        when (kind) {
            PayCostsRouteKind.CollectEvidence,
            PayCostsRouteKind.TeamworkCost,
            -> {
                require(request.costSelectionWeights.size == request.candidateRefs.size) {
                    "$kind requires one weight per candidate"
                }
                request.costSelectionWeights.map { it.coerceAtLeast(0) }
            }
            PayCostsRouteKind.Sacrifice,
            PayCostsRouteKind.SelectCostExileFromGrave,
            PayCostsRouteKind.SelectCostReturnAttacker,
            PayCostsRouteKind.StationTapCost,
            PayCostsRouteKind.EnlistCost,
            PayCostsRouteKind.ConvokeCost,
            PayCostsRouteKind.ImproviseCost,
            PayCostsRouteKind.WaterbendCost,
            -> List(request.candidateRefs.size) { 1 }
        }

    private fun selectionRange(
        kind: PayCostsRouteKind,
        request: PromptRequest,
    ): Pair<Int, Int> =
        when (kind) {
            PayCostsRouteKind.CollectEvidence,
            PayCostsRouteKind.TeamworkCost,
            -> request.min.coerceAtLeast(0) to request.max.coerceAtLeast(request.min)
            PayCostsRouteKind.Sacrifice,
            PayCostsRouteKind.SelectCostExileFromGrave,
            PayCostsRouteKind.SelectCostReturnAttacker,
            PayCostsRouteKind.StationTapCost,
            PayCostsRouteKind.EnlistCost,
            PayCostsRouteKind.ConvokeCost,
            PayCostsRouteKind.ImproviseCost,
            PayCostsRouteKind.WaterbendCost,
            -> {
                val exact = request.max.coerceAtLeast(1)
                exact to exact
            }
        }
}
