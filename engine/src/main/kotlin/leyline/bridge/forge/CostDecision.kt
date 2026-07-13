package leyline.bridge.forge

import com.google.common.collect.Lists
import forge.card.MagicColor
import forge.game.GameEntityCounterTable
import forge.game.ability.AbilityUtils
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
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.toCandidateRefs
import org.slf4j.LoggerFactory

/**
 * Web-based cost decision maker: routes interactive cost choices through the
 * [InteractivePromptBridge] instead of desktop Input* classes.
 *
 * Extends Forge's [HumanCostDecision]; cost families pay through shared
 * controller choice hooks and inherit the Forge visitors. Only two shapes
 * remain overridden here:
 * - [CostExile] aggregate/constrained selections (total CMC, mana symbols,
 *   card-type counts, shared card type), pending a weighted or constrained
 *   selection seam in Forge.
 * - [CostRemoveAnyCounter], a multi-stage interaction (repeated card choice
 *   with per-card counter labels plus counter-type choice) with no narrow
 *   controller seam yet.
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

    private val mandatory: Boolean = sa.payCosts?.isMandatory ?: false

    // ═══════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════

    private fun confirmAction(message: String): Boolean {
        val cardView = ability.cardView
        return controller.gui.confirm(cardView, message)
    }

    /**
     * Bridge-based card selection replacing desktop InputSelectCardsFromList.
     * Returns null if cancelled.
     */
    private fun selectCards(
        message: String,
        cards: CardCollectionView,
        min: Int,
        max: Int,
        cancelAllowed: Boolean = true,
        semantic: PromptSemantic = PromptSemantic.Generic,
        costSelectionWeights: List<Int> = emptyList(),
        minSelectionWeight: Int? = null,
    ): CardCollection? {
        if (cards.isEmpty()) return if (cancelAllowed) null else CardCollection()
        if (cards.size <= min && !cancelAllowed) {
            return CardCollection(cards)
        }
        val labels = cards.map { it.name }
        val refs = cards.toCandidateRefs()
        val request =
            PromptRequest(
                promptType = "choose_cards",
                message = message,
                options = labels,
                min = min,
                max = max,
                defaultIndex = 0,
                candidateRefs = refs,
                semantic = semantic,
                costSelectionWeights = costSelectionWeights,
                minSelectionWeight = minSelectionWeight,
                sourceEntityId = source.id.takeIf { it > 0 },
            )
        val indices = bridge.requestChoice(request, targetingSa = ability)
        if (indices.isEmpty() && cancelAllowed) return null
        val selected = CardCollection()
        for (idx in indices) {
            if (idx in 0 until cards.size) selected.add(cards[idx])
        }
        return if (selected.size >= min) selected else null
    }

    /**
     * Exile shapes that still need Leyline-side selection: aggregate totals
     * (CMC, mana symbols, card-type counts) and shared-card-type constrained
     * selection outside cross-player same-zone payment. Everything else
     * inherits the Forge visitor.
     */
    private fun isSpecializedExileShape(cost: CostExile): Boolean {
        val aggregateMarkers = listOf("+withTotalCMCEQ", "+withTotalCMCGE", "+withTotalManaSymbols_", "+withTypesGE")
        if (aggregateMarkers.any(cost.type::contains)) return true
        return cost.type.contains("+withSharedCardType") && cost.zoneRestriction != 0
    }

    // ═══════════════════════════════════════════════════════════════════
    // Interactive visit() methods (bridge-based card selection)
    // ═══════════════════════════════════════════════════════════════════

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    override fun visit(cost: CostExile): PaymentDecision? {
        if (!isSpecializedExileShape(cost)) return super.visit(cost)

        var type = cost.type
        if (type.contains("FromTopGrave")) {
            type = TextUtil.fastReplace(type, "FromTopGrave", "")
        }
        var totalCMCgreater = false
        var totalM: String? = null
        if (type.contains("+withTotalCMCEQ")) {
            totalM = type.split("withTotalCMCEQ")[1]
            type = TextUtil.fastReplace(type, "+withTotalCMCEQ$totalM", "")
        }
        if (type.contains("+withTotalCMCGE")) {
            totalCMCgreater = true
            totalM = type.split("withTotalCMCGE")[1]
            type = TextUtil.fastReplace(type, "+withTotalCMCGE$totalM", "")
        }
        var totalManaSymbolsColor: String? = null
        var totalManaSymbolsCmp: String? = null
        if (type.contains("+withTotalManaSymbols_")) {
            val details = type.split("withTotalManaSymbols_")[1].split("_")
            totalManaSymbolsColor = details[0]
            totalManaSymbolsCmp = details[1]
            type = TextUtil.fastReplace(type, "+withTotalManaSymbols_${totalManaSymbolsColor}_$totalManaSymbolsCmp", "")
        }
        var sharedType = false
        if (type.contains("+withSharedCardType")) {
            sharedType = true
            type = TextUtil.fastReplace(type, "+withSharedCardType", "")
        }
        var nTypes = -1
        if (type.contains("+withTypesGE")) {
            val num = type.split("withTypesGE")[1]
            type = TextUtil.fastReplace(type, "+withTypesGE$num", "")
            nTypes = num.toInt()
        }

        val game = player.game
        var list: CardCollection =
            if (cost.zoneRestriction != 1) {
                CardCollection(game.getCardsIn(cost.from))
            } else {
                CardCollection(player.getCardsIn(cost.from))
            }
        list = CardLists.getValidCards(list, type.split(";").toTypedArray(), player, source, ability)
        list = CardLists.filter(list, CardPredicates.canExiledBy(ability, isEffect))

        if (totalM != null) {
            val needed = cost.amount.split("\\+".toRegex())[0].toInt()
            val total = AbilityUtils.calculateAmount(source, totalM, ability)
            val selected =
                selectCards(
                    Localizer.getInstance().getMessage("lblSelectToExile", Lang.getNumeral(needed)),
                    list,
                    needed,
                    list.size,
                    cancelAllowed = true,
                ) ?: return null
            val sum = CardLists.getTotalCMC(selected)
            if ((sum != total && !totalCMCgreater) || (sum < total && totalCMCgreater)) return null
            return PaymentDecision.card(selected)
        }

        if (totalManaSymbolsColor != null && totalManaSymbolsCmp != null) {
            val needed = cost.amount.split("\\+".toRegex())[0].toInt()
            val total = AbilityUtils.calculateAmount(source, totalM, ability)
            val selected =
                selectCards(
                    Localizer.getInstance().getMessage("lblSelectToExile", Lang.getNumeral(needed)),
                    list,
                    needed,
                    list.size,
                    cancelAllowed = true,
                ) ?: return null
            val sum = CardLists.getTotalChroma(selected, MagicColor.fromName(totalManaSymbolsColor))
            val right = AbilityUtils.calculateAmount(source, totalManaSymbolsCmp.substring(2), ability)
            if (!Expressions.compare(sum, totalManaSymbolsCmp, right)) return null
            return PaymentDecision.card(selected)
        }

        if (nTypes > -1) {
            val selected =
                selectCards(
                    if (cost.amount == "X") {
                        Localizer.getInstance().getMessage("lblSelectAnyNumToExile")
                    } else {
                        Localizer.getInstance().getMessage("lblSelectToExile", Lang.getNumeral(nTypes))
                    },
                    list,
                    1,
                    list.size,
                    cancelAllowed = true,
                ) ?: return null
            if (!Expressions.compare(AbilityUtils.countCardTypesFromList(list, false), "GE", nTypes)) return null
            return PaymentDecision.card(selected)
        }

        // Shared-card-type constrained selection (zoneRestriction != 0 by predicate).
        val c = cost.getAbilityAmount(ability)
        if (list.size < c) return null
        if (c == 0) return PaymentDecision.number(c)

        if (cost.zoneRestriction == -1 && ability.isTrigger && c == 1 && list.size == 1) {
            return if (confirmAction(Localizer.getInstance().getMessage("lblExileConfirm", list.first().translatedName))) {
                PaymentDecision.card(list.first())
            } else {
                null
            }
        }
        val origin = Lists.newArrayList(cost.from)
        val required = if (sharedType) " (must share a card type)" else ""
        val chosen =
            controller.chooseCardsForZoneChange(
                ZoneType.Exile,
                origin,
                ability,
                list,
                if (mandatory) c else 0,
                c,
                null,
                cost.toString(c) + required,
                null,
            )
        if (chosen.size < c) return null
        if (sharedType && !chosen[1].sharesCardTypeWith(chosen[0])) return null
        return PaymentDecision.card(chosen)
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
            // Inline, not selectCards: labels need per-card counter counts; selectCards only
            // exposes card names.
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
