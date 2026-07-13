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
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.types.toCandidateRefs
import org.slf4j.LoggerFactory

/**
 * Web-based cost decision maker: routes interactive cost choices through the
 * [InteractivePromptBridge] instead of desktop Input* classes.
 *
 * Extends Forge's [HumanCostDecision]; cost families pay through shared
 * controller choice hooks and inherit the Forge visitors. One shape remains
 * overridden here: [CostRemoveAnyCounter], a multi-stage interaction
 * (repeated card choice with per-card counter labels plus counter-type
 * choice) with no narrow controller seam yet.
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
