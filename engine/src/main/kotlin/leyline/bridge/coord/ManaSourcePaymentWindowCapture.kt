package leyline.bridge.coord

import forge.card.mana.ManaCostShard
import forge.game.card.Card
import leyline.bridge.handoff.ManaSourcePaymentCandidateValue
import leyline.bridge.handoff.ManaSourcePaymentKind
import leyline.bridge.handoff.ManaSourcePaymentSelectionValue
import leyline.bridge.handoff.ManaSourcePaymentWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ManaColorMapping
import leyline.bridge.types.ManaCostText
import leyline.game.data.KeywordAbilityIds
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/** Engine-thread freezing and immutable re-planning for iterative mana-source payments. */
internal class ManaSourcePaymentWindowCapture(
    private val owner: MatchCutCoordinator,
) {
    data class Initial(
        val value: ManaSourcePaymentWindowValue,
        val handlesByOption: Map<Int, Card>,
    )

    fun initial(
        request: PromptRequest,
        candidateHandles: List<Card>,
    ): Initial {
        val route = request.route as? ResolvedPromptRoute.PayCosts ?: error("Mana-source payment requires a PayCosts route")
        val kind = route.descriptor.manaSourcePayment ?: error("PayCosts route is not an iterative mana-source payment")
        val handlesById = candidateHandles.associateBy { it.id }
        require(handlesById.size == candidateHandles.size) { "Mana-source payment candidates must have unique Forge ids" }
        val handlesByOption =
            request.candidateRefs.associate { ref ->
                ref.index to (handlesById[ref.entityId] ?: error("Missing mana-source candidate ${ref.entityId}"))
            }
        require(handlesByOption.size == request.candidateRefs.size) { "Mana-source payment option indices must be unique" }
        val source = request.sourceEntityId?.let(::ForgeCardId)
        val sourceGrpId =
            source
                ?.let(owner.bridge::findCard)
                ?.let { card -> owner.bridge.cardRepository.findGrpIdByName(card.name) }
                ?: request.sourceCardName?.let(owner.bridge.cardRepository::findGrpIdByName)
                ?: 0
        val sourceAbilityGrpId =
            if (kind == ManaSourcePaymentKind.Waterbend && sourceGrpId != 0) {
                owner.bridge.cardRepository.findKeywordAbilityGrpId(sourceGrpId, KeywordAbilityIds.WATERBEND) ?: 0
            } else {
                0
            }
        val value =
            buildValue(
                kind = kind,
                handlesByOption = handlesByOption,
                selected = emptyList(),
                manaCost = request.waterbendManaCost,
                source = source,
                sourceGrpId = sourceGrpId,
                sourceAbilityGrpId = sourceAbilityGrpId,
                costString = request.waterbendCostString,
                defaultOptionIndex = request.defaultIndex,
                maxSelection = request.max,
            )
        return Initial(value, handlesByOption)
    }

    fun select(
        prior: ManaSourcePaymentWindowValue,
        handlesByOption: Map<Int, Card>,
        selectedOptions: List<Int>,
    ): ManaSourcePaymentWindowValue {
        val byOption = prior.candidates.associateBy { it.originalOptionIndex }
        val additions =
            selectedOptions.map { option ->
                val candidate = byOption[option] ?: error("Mana-source option $option is not currently available")
                ManaSourcePaymentSelectionValue(option, candidate.forgeCardId, candidate.paymentColor, candidate.costColor)
            }
        val nextSelections = prior.selections + additions
        val nextManaCost = reduceManaCost(prior.kind, prior.manaCost, additions)
        return buildValue(
            kind = prior.kind,
            handlesByOption = handlesByOption.filterKeys { option -> nextSelections.none { it.originalOptionIndex == option } },
            selected = nextSelections,
            manaCost = nextManaCost,
            source = prior.sourceForgeCardId,
            sourceGrpId = prior.sourceGrpId,
            sourceAbilityGrpId = prior.sourceAbilityGrpId,
            costString = ManaCostText.clientText(nextManaCost).takeIf { it.isNotEmpty() },
            defaultOptionIndex = prior.defaultOptionIndex,
            maxSelection = prior.maxSelection,
        )
    }

    private fun buildValue(
        kind: ManaSourcePaymentKind,
        handlesByOption: Map<Int, Card>,
        selected: List<ManaSourcePaymentSelectionValue>,
        manaCost: List<Pair<ManaColor, Int>>,
        source: ForgeCardId?,
        sourceGrpId: Int,
        sourceAbilityGrpId: Int,
        costString: String?,
        defaultOptionIndex: Int,
        maxSelection: Int,
    ): ManaSourcePaymentWindowValue {
        val convokeCostCounts = ManaColorMapping.paymentShardCounts(manaCost)
        val convokeShards =
            if (kind == ManaSourcePaymentKind.Convoke) {
                ConvokeShardAssigner
                    .assign(handlesByOption.entries.toList(), convokeCostCounts) { it.value.color }
                    .associate { (entry, shard) -> entry.key to shard }
            } else {
                emptyMap()
            }
        val candidates =
            handlesByOption.mapNotNull { (option, card) ->
                if (kind != ManaSourcePaymentKind.Convoke && convokeCostCounts.getOrDefault(ManaCostShard.GENERIC, 0) == 0) {
                    return@mapNotNull null
                }
                val shard =
                    if (kind == ManaSourcePaymentKind.Convoke) {
                        convokeShards[option]
                            ?: ConvokeShardAssigner
                                .assign(listOf(card), convokeCostCounts) { it.color }
                                .singleOrNull()
                                ?.second
                            ?: return@mapNotNull null
                    } else {
                        null
                    }
                val forgeId = ForgeCardId(card.id)
                val instanceId =
                    owner.bridge
                        .projectionStateSnapshot()
                        .identities.forgeIdToInstanceId[forgeId]
                val grpId =
                    instanceId?.let { owner.bridge.resolveGrpId(card, it.value) }?.takeIf { it != 0 }
                        ?: owner.bridge.cardRepository.findGrpIdByName(card.name)
                        ?: 0
                ManaSourcePaymentCandidateValue(
                    originalOptionIndex = option,
                    forgeCardId = forgeId,
                    grpId = grpId,
                    fromCreature = card.isCreature,
                    paymentColor = shard?.let(ManaColorMapping::paymentWireColor) ?: ManaColor.Colorless_afc9,
                    costColor = shard?.let(ManaColorMapping::paymentCostColor) ?: ManaColor.Generic,
                )
            }
        return ManaSourcePaymentWindowValue(
            kind = kind,
            candidates = candidates,
            selections = selected,
            manaCost = manaCost,
            sourceForgeCardId = source,
            sourceGrpId = sourceGrpId,
            sourceAbilityGrpId = sourceAbilityGrpId,
            costString = costString,
            defaultOptionIndex = defaultOptionIndex,
            maxSelection = maxSelection,
        )
    }

    private fun reduceManaCost(
        kind: ManaSourcePaymentKind,
        cost: List<Pair<ManaColor, Int>>,
        selections: List<ManaSourcePaymentSelectionValue>,
    ): List<Pair<ManaColor, Int>> {
        val remaining = cost.associate { it.first to it.second }.toMutableMap()
        for (selection in selections) {
            val color = if (kind == ManaSourcePaymentKind.Convoke) selection.costColor else ManaColor.Generic
            val next = (remaining[color] ?: 0) - 1
            if (next <= 0) remaining.remove(color) else remaining[color] = next
        }
        return cost.mapNotNull { (color, _) -> remaining[color]?.takeIf { it > 0 }?.let { color to it } }
    }
}
