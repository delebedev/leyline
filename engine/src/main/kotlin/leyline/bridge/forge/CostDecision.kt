package leyline.bridge.forge

import com.google.common.collect.Lists
import forge.game.GameEntityCounterTable
import forge.game.card.*
import forge.game.cost.*
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import forge.player.HumanCostDecision
import forge.player.PlayerControllerHuman
import forge.util.*
import leyline.bridge.handoff.GatherCounterType
import leyline.bridge.handoff.GatherCountersSourceValue
import leyline.bridge.handoff.GatherCountersWindowInput
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PayCostsPromptSourceInput
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.toCandidateRefs
import org.slf4j.LoggerFactory

/**
 * Web-based cost decision maker: routes interactive cost choices through the
 * [InteractivePromptBridge] instead of desktop Input* classes.
 *
 * Extends Forge's [HumanCostDecision]; cost families pay through shared
 * controller choice hooks and inherit the Forge visitors. The grounded
 * Hopeful Initiate counter row uses the match-scoped GatherCounters seam;
 * unsupported counter costs retain Forge's residual chooser below.
 */
class CostDecision(
    private val controller: PlayerControllerHuman,
    p: Player,
    sa: SpellAbility,
    effect: Boolean,
    private val bridge: InteractivePromptBridge,
    prompt: String? = null,
) : HumanCostDecision(controller, p, sa, effect, prompt) {
    companion object {
        @Suppress("UnusedPrivateProperty")
        private val log = LoggerFactory.getLogger(CostDecision::class.java)
    }

    override fun visit(cost: CostRemoveAnyCounter): PaymentDecision? {
        if (isGroundedGatherCounters(cost)) return gatherCounters(cost)
        return visitResidualCounterCost(cost)
    }

    private fun gatherCounters(cost: CostRemoveAnyCounter): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        val list =
            CardLists
                .getValidCards(
                    player.getCardsIn(ZoneType.Battlefield),
                    cost.type.split(";").toTypedArray(),
                    player,
                    source,
                    ability,
                ).filter { card ->
                    card.controller == player &&
                        card.isCreature &&
                        card.canRemoveCounters(CounterEnumType.P1P1) &&
                        card.getCounters(CounterEnumType.P1P1) > 0
                }
        if (list.isEmpty() || list.sumOf { it.getCounters(CounterEnumType.P1P1) } < c) return null
        val root = ability.rootAbility
        val window =
            GatherCountersWindowInput(
                promptSource =
                    PayCostsPromptSourceInput.StackAbility(
                        forgeAbilityId = ability.id,
                        sourceForgeCardId = ForgeCardId(root.hostCard.id),
                        abilityDefinitionId = root.definitionId,
                        targetForgeCardIds =
                            root.targets
                                ?.targetCards
                                .orEmpty()
                                .map { ForgeCardId(it.id) },
                    ),
                sources = list.map { GatherCountersSourceValue(ForgeCardId(it.id), it.getCounters(CounterEnumType.P1P1)) },
                amountToGather = c,
                counterType = GatherCounterType.P1P1,
            )
        val result = bridge.requestGatherCounters(window, list.toList())
        if (result.isEmpty) return null
        val counterTable = GameEntityCounterTable()
        result.payments.forEach { payment ->
            counterTable.put(null, payment.handle, CounterEnumType.P1P1, payment.amount)
        }
        return PaymentDecision.counters(counterTable)
    }

    private fun isGroundedGatherCounters(cost: CostRemoveAnyCounter): Boolean {
        val root = ability.rootAbility
        return cost.getAbilityAmount(ability) == 2 &&
            cost.counter == CounterEnumType.P1P1 &&
            cost.type == "Creature" &&
            !cost.payCostFromSource() &&
            !root.isSpell &&
            root.isActivatedAbility()
    }

    private fun visitResidualCounterCost(cost: CostRemoveAnyCounter): PaymentDecision? {
        var c = cost.getAbilityAmount(ability)
        var list: CardCollectionView =
            CardLists.getValidCards(
                player.getCardsIn(ZoneType.Battlefield),
                cost.type.split(";").toTypedArray(),
                player,
                source,
                ability,
            )
        list = CardLists.filter(list, CardPredicates.hasCounters())
        if (list.isEmpty()) return null

        val counterTable = GameEntityCounterTable()
        while (c > 0) {
            val labels =
                list.map { card ->
                    val counterStr = card.counters.entrySet().joinToString(", ") { "${it.element.name}: ${it.count}" }
                    "${card.name} ($counterStr)"
                }
            val refs = list.toCandidateRefs()
            // Inline prompt: labels need per-card counter counts, which the
            // shared card-selection helpers do not expose.
            val request =
                PromptRequest(
                    promptType = "choose_cards",
                    message =
                        Localizer.getInstance().getMessage(
                            "lblRemoveNTargetCounterFromCardPayCostSelect",
                            c.toString(),
                            if (cost.counter != null) " ${cost.counter.name.lowercase()}" else "",
                            cost.descriptiveType,
                        ),
                    options = labels,
                    min = 1,
                    max = 1,
                    defaultIndex = 0,
                    candidateRefs = refs,
                )
            val indices = bridge.requestChoice(request)
            val idx = indices.firstOrNull() ?: return null
            val card = list.toList().getOrNull(idx) ?: return null

            val cType =
                if (cost.counter != null) {
                    cost.counter
                } else {
                    val cmap = counterTable.filterToRemove(card)
                    if (cmap.elementSet().size == 1) {
                        cmap.elementSet().first()
                    } else {
                        val counterTypes = Lists.newArrayList(cmap.elementSet())
                        controller.chooseCounterType(
                            counterTypes,
                            ability,
                            Localizer.getInstance().getMessage("lblSelectCountersTypeToRemove"),
                            null,
                        )
                    }
                }
            if (cType == null || !card.canRemoveCounters(cType)) return null
            if (card.getCounters(cType) <= counterTable.get(null, card, cType)) return null

            counterTable.put(null, card, cType, 1)
            c--
        }
        return PaymentDecision.counters(counterTable)
    }
}
