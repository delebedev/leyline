package leyline.bridge.forge

import com.google.common.collect.ImmutableList
import com.google.common.collect.Lists
import forge.card.MagicColor
import forge.game.GameEntityCounterTable
import forge.game.GameEntityView
import forge.game.GameEntityViewMap
import forge.game.ability.AbilityUtils
import forge.game.card.*
import forge.game.cost.*
import forge.game.keyword.Keyword
import forge.game.player.Player
import forge.game.player.PlayerView
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import forge.player.HumanCostDecision
import forge.player.PlayerControllerHuman
import forge.util.*
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.interaction.CostCardSelectionPlan
import leyline.bridge.interaction.CostDecisionPlanner
import leyline.bridge.types.toCandidateRefs
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Web-based cost decision maker: routes interactive cost choices through the
 * [InteractivePromptBridge] instead of desktop Input* classes.
 *
 * Extends Forge's [HumanCostDecision], retaining bridge-specific visitor
 * overrides while shared visitors migrate into Forge.
 * Non-interactive costs (confirm-only) go through [ClientGuiGame.confirm];
 * interactive card selections go through the [selectCards] helper.
 *
 * See ADR-010 Seam 1 spike for design rationale.
 */
@Suppress("LargeClass")
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

    private fun selectTotalPowerTapCost(
        cost: CostTapType,
        typeList: CardCollectionView,
        totalPower: Int,
    ): PaymentDecision? {
        val plan =
            if (cost is CostTeamwork) {
                CostDecisionPlanner
                    .teamworkPlan(
                        totalPower = totalPower,
                        powers = typeList.map { (it.netPower ?: 0).coerceAtLeast(0) },
                    ).toCardSelectionPlan()
            } else {
                CostCardSelectionPlan(PromptSemantic.Generic)
            }
        val selected =
            selectCards(
                Localizer.getInstance().getMessage("lblSelectACreatureToTap"),
                typeList,
                1,
                typeList.size,
                cancelAllowed = false,
                semantic = plan.semantic,
                costSelectionWeights = plan.costSelectionWeights,
                minSelectionWeight = plan.minSelectionWeight,
            ) ?: return null
        if (CardLists.getTotalPower(selected, ability) < totalPower) return null
        return PaymentDecision.card(selected)
    }

    private fun isOrdinaryExactTapCost(cost: CostTapType): Boolean =
        cost.amount != "Any" &&
            !cost.type.contains(".sharesCreatureTypeWith") &&
            !cost.type.contains("+withTotalPowerGE") &&
            cost !is CostTeamwork &&
            !ability.isCrew &&
            !ability.isKeyword(Keyword.STATION)

    private fun isOrdinaryExileCost(cost: CostExile): Boolean {
        if (cost.payCostFromSource() || cost.type == "OriginalHost" || cost.type == "All") return true
        if (
            listOf(
                "+withTotalCMCEQ",
                "+withTotalCMCGE",
                "+withTotalManaSymbols_",
                "+withSharedCardType",
                "+withTypesGE",
            ).any(cost.type::contains)
        ) {
            return false
        }
        if (cost.type.contains("FromTopGrave")) return true
        if (cost.from.size != 1) return false
        return cost.from[0] == ZoneType.Battlefield ||
            cost.from[0] == ZoneType.Hand ||
            cost.from[0] == ZoneType.Library
    }

    // ═══════════════════════════════════════════════════════════════════
    // Non-interactive visit() methods
    // ═══════════════════════════════════════════════════════════════════

    override fun visit(cost: CostAddMana): PaymentDecision = PaymentDecision.number(cost.getAbilityAmount(ability))

    override fun visit(cost: CostFlipCoin): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        return if (confirmAction(Localizer.getInstance().getMessage("lblDoYouWantFlipNCoinAction", c.toString()))) {
            PaymentDecision.number(c)
        } else {
            null
        }
    }

    override fun visit(cost: CostRollDice): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        return if (confirmAction(Localizer.getInstance().getMessage("lblDoYouWantRollNDiceAction", c.toString(), "d${cost.type}"))) {
            PaymentDecision.number(c)
        } else {
            null
        }
    }

    override fun visit(cost: CostPartMana): PaymentDecision = PaymentDecision(0)

    override fun visit(cost: CostTap): PaymentDecision = PaymentDecision.number(1)

    override fun visit(cost: CostUntap): PaymentDecision = PaymentDecision.number(1)

    override fun visit(cost: CostRevealChosen): PaymentDecision = PaymentDecision.number(1)

    override fun visit(cost: CostExileFromStack): PaymentDecision? {
        val game = player.game
        val saList = mutableListOf<SpellAbility>()
        val descList = mutableListOf<String>()
        for (si in game.stack) {
            val stC = si.sourceCard
            val stSA = si.spellAbility.rootAbility
            if (stC.isValid(cost.type.split(";").toTypedArray(), ability.activatingPlayer, source, ability) && stSA.isSpell) {
                saList.add(stSA)
                descList.add(
                    if (stC.isCopiedSpell) {
                        "${stSA.stackDescription} (Copied Spell)"
                    } else {
                        stSA.stackDescription
                    },
                )
            }
        }
        if (cost.type == "All") return PaymentDecision.spellabilities(saList)
        val c = cost.getAbilityAmount(ability)
        if (saList.size < c) return null
        val exiled = mutableListOf<SpellAbility>()
        repeat(c) {
            val o =
                controller.gui.oneOrNone(
                    Localizer.getInstance().getMessage("lblExileFromStack"),
                    descList,
                ) ?: return null
            val toExile = saList[descList.indexOf(o)]
            saList.remove(toExile)
            descList.remove(o)
            exiled.add(toExile)
        }
        return PaymentDecision.spellabilities(exiled)
    }

    override fun visit(cost: CostBlight): PaymentDecision? = visit(cost as CostPutCounter)

    // ═══════════════════════════════════════════════════════════════════
    // Interactive visit() methods (bridge-based card selection)
    // ═══════════════════════════════════════════════════════════════════

    override fun visit(cost: CostExile): PaymentDecision? {
        if (isOrdinaryExileCost(cost)) return super.visit(cost)

        var type = cost.type
        var onlyPayable: Card? = null
        if (cost.payCostFromSource()) onlyPayable = source
        if (type == "OriginalHost") onlyPayable = ability.originalHost

        if (onlyPayable != null) {
            if (onlyPayable.canExiledBy(ability, isEffect) &&
                onlyPayable.zone == player.getZone(cost.from[0]) &&
                confirmAction(Localizer.getInstance().getMessage("lblExileConfirm", onlyPayable.translatedName))
            ) {
                return PaymentDecision.card(onlyPayable)
            }
            return null
        }

        var fromTopGrave = false
        if (type.contains("FromTopGrave")) {
            type = TextUtil.fastReplace(type, "FromTopGrave", "")
            fromTopGrave = true
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

        if (type == "All") {
            return if (confirmAction(
                    Localizer.getInstance().getMessage("lblExileNCardsFromYourZone", list.size, cost.from[0].translatedName),
                )
            ) {
                PaymentDecision.card(list)
            } else {
                null
            }
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

        val c = cost.getAbilityAmount(ability)
        if (list.size < c) return null
        if (c == 0) return PaymentDecision.number(c)

        if (cost.from.size == 1) {
            val fromZone = cost.from[0]
            if (fromZone == ZoneType.Battlefield || fromZone == ZoneType.Hand) {
                val selected =
                    selectCards(
                        Localizer.getInstance().getMessage("lblExileNCardsFromYourZone", "%d", fromZone.translatedName),
                        list,
                        c,
                        c,
                        cancelAllowed = !mandatory,
                    ) ?: return null
                return PaymentDecision.card(selected)
            }
            if (fromZone == ZoneType.Library) {
                return if (confirmAction(Localizer.getInstance().getMessage("lblExileNCardFromYourTopLibraryConfirm"))) {
                    PaymentDecision.card(player.getCardsIn(ZoneType.Library, c))
                } else {
                    null
                }
            }
        }

        if (fromTopGrave) {
            Collections.reverse(list)
            return PaymentDecision.card(list.subList(0, c.coerceAtMost(list.size)))
        }

        if (cost.zoneRestriction != 0) {
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

        // Exile from same zone (cross-player)
        val players = game.players
        val payableZone = mutableListOf<Player>()
        for (p in players) {
            val enoughType = CardLists.filter(list, CardPredicates.isOwner(p))
            if (enoughType.size < c) {
                list.removeAll(enoughType)
            } else {
                payableZone.add(p)
            }
        }
        return exileFromSame(cost, list, c, payableZone)
    }

    private fun exileFromSame(
        cost: CostExile,
        list: CardCollectionView,
        nNeeded: Int,
        payableZone: List<Player>,
    ): PaymentDecision? {
        if (nNeeded == 0) return PaymentDecision.number(0)
        val gameCachePlayer: GameEntityViewMap<Player, PlayerView> = GameEntityView.getMap(payableZone)
        val pv =
            controller.gui.oneOrNone(
                Localizer.getInstance().getMessage("lblExileFromWhoseZone", cost.from[0].translatedName),
                gameCachePlayer.trackableKeys,
            )
        if (pv == null || !gameCachePlayer.containsKey(pv)) return null
        val p = gameCachePlayer[pv]
        val typeList = CardLists.filter(list, CardPredicates.isOwner(p))
        if (typeList.size < nNeeded) return null
        val gameCacheExile: GameEntityViewMap<Card, CardView> = GameEntityView.getMap(typeList)
        val views =
            controller.gui.many(
                Localizer.getInstance().getMessage("lblExileFromZone", cost.from[0].translatedName),
                Localizer.getInstance().getMessage("lblToBeExiled"),
                nNeeded,
                gameCacheExile.trackableKeys,
                null,
            )
        val result = Lists.newArrayList<Card>()
        gameCacheExile.addToList(views, result)
        return PaymentDecision.card(result)
    }

    override fun visit(cost: CostPutCounter): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        if (cost.payCostFromSource()) {
            if (ability.hasParam("UnlessCost") &&
                !confirmAction(
                    Localizer.getInstance().getMessage(
                        "lblPutNTypeCounterOnTarget",
                        c.toString(),
                        cost.counter.name,
                        ability.hostCard.displayName,
                    ),
                )
            ) {
                return null
            }
            return PaymentDecision.card(source)
        }
        var typeList: CardCollectionView =
            CardLists.getValidCards(
                source.game.getCardsIn(ZoneType.Battlefield),
                cost.type.split(";").toTypedArray(),
                player,
                ability.hostCard,
                ability,
            )
        typeList = CardLists.filter(typeList, CardPredicates.canReceiveCounters(cost.counter))
        if (typeList.isEmpty()) return null
        val selected =
            selectCards(
                Localizer.getInstance().getMessage("lblPutNTypeCounterOnTarget", c.toString(), cost.counter.name, cost.descriptiveType),
                typeList,
                1,
                1,
                cancelAllowed = !mandatory,
            ) ?: return null
        return PaymentDecision.card(selected)
    }

    override fun visit(cost: CostPutCounterYou): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        return if (confirmAction(
                Localizer.getInstance().getMessage("lblPutNTypeCounterOnTarget", c, cost.counter.name, player.toString()),
            )
        ) {
            PaymentDecision.number(c)
        } else {
            null
        }
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

    override fun visit(cost: CostRemoveCounter): PaymentDecision? {
        val amount = cost.amount
        val type = cost.type
        val cntrs = cost.counter
        val anyCounters = cntrs == null

        var cntRemoved = 1
        if (amount != "All") {
            cntRemoved = cost.getAbilityAmount(ability)
        }

        if (cost.payCostFromSource()) {
            val maxCounters = if (anyCounters) source.numAllCounters else source.getCounters(cntrs)
            if (amount == "All") {
                val prompt =
                    Localizer.getInstance().getMessage("lblRemoveAllCountersConfirm") +
                        if (anyCounters) "" else " (${cntrs!!.name})"
                if (!confirmAction(prompt)) return null
                cntRemoved = maxCounters
            } else if (ability != null && !ability.isPwAbility) {
                if (maxCounters < cntRemoved) return null
                if (!confirmAction(
                        Localizer.getInstance().getMessage(
                            "lblRemoveNTargetCounterFromCardPayCostConfirm",
                            amount,
                            if (anyCounters) "" else cntrs!!.name.lowercase(),
                            source.translatedName,
                        ),
                    )
                ) {
                    return null
                }
            }
            if (maxCounters < cntRemoved) return null
            val counterTable = generateCounterTable(source, cntrs, if (cntRemoved >= 0) cntRemoved else maxCounters)
            return if (counterTable.isEmpty) null else PaymentDecision.counters(counterTable)
        }

        if (type == "OriginalHost") {
            val origHost = ability.originalHost
            val maxCounters = if (anyCounters) origHost.numAllCounters else origHost.getCounters(cntrs)
            if (amount == "All") cntRemoved = maxCounters
            if (maxCounters < cntRemoved) return null
            val counterTable = generateCounterTable(origHost, cntrs, if (cntRemoved >= 0) cntRemoved else maxCounters)
            return if (counterTable.isEmpty) null else PaymentDecision.counters(counterTable)
        }

        var validCards: CardCollectionView =
            CardLists.getValidCards(
                player.getCardsIn(cost.zone),
                type.split(";").toTypedArray(),
                player,
                source,
                ability,
            )
        validCards =
            if (anyCounters) {
                CardLists.filterAnyCounters(validCards, cntRemoved)
            } else {
                CardLists.filter(validCards, CardPredicates.hasCounter(cntrs, cntRemoved))
            }
        if (validCards.isEmpty()) return null

        val selected =
            selectCards(
                Localizer.getInstance().getMessage(
                    "lblRemoveCountersFromAInZoneCard",
                    Lang.joinHomogenous(cost.zone) { z -> z.translatedName },
                ),
                validCards,
                1,
                1,
                cancelAllowed = true,
            )
        val card = selected?.first() ?: return null

        val counterTable = generateCounterTable(card, cntrs, cntRemoved)
        return if (counterTable.isEmpty) null else PaymentDecision.counters(counterTable)
    }

    private fun generateCounterTable(
        c: Card,
        cType: CounterType?,
        cntToRemove: Int,
    ): GameEntityCounterTable {
        val counterTable = GameEntityCounterTable()
        if (cType != null) {
            counterTable.put(null, c, cType, cntToRemove)
        } else {
            val cMap = counterTable.filterToRemove(c)
            for (ct in ImmutableList.copyOf(cMap.elementSet())) {
                if (!c.canRemoveCounters(ct)) cMap.remove(ct, cMap.count(ct))
            }
            if (cMap.isEmpty()) return counterTable
            if (cMap.elementSet().size == 1) {
                counterTable.put(null, c, cMap.entrySet().first().element, cntToRemove)
            } else {
                var remaining = cntToRemove
                while (remaining > 0) {
                    val pc = c.controller.controller
                    val chosen =
                        pc.chooseCounterType(
                            Lists.newArrayList(cMap.elementSet()),
                            ability,
                            Localizer.getInstance().getMessage("lblSelectCountersTypeToRemove"),
                            null,
                        ) ?: break
                    val max = remaining.coerceAtMost(cMap.count(chosen))
                    val totalRemaining = cMap.entrySet().sumOf { it.count }
                    val min = 1.coerceAtLeast(max - totalRemaining)
                    val chosenAmount =
                        pc.chooseNumber(
                            ability,
                            Localizer.getInstance().getMessage("lblSelectRemoveCountersNumberOfTarget", chosen.name),
                            min,
                            max,
                            null,
                        )
                    if (chosenAmount > 0) {
                        counterTable.put(null, c, chosen, chosenAmount)
                        cMap.clear()
                        val refreshedCounters = counterTable.filterToRemove(c)
                        if (refreshedCounters.isNotEmpty()) {
                            check(cMap.addAll(refreshedCounters))
                        }
                    }
                    remaining -= chosenAmount
                }
            }
        }
        return counterTable
    }

    override fun visit(cost: CostTapType): PaymentDecision? {
        if (isOrdinaryExactTapCost(cost)) return super.visit(cost)

        var type = cost.type
        val amount = cost.amount

        if (type == "OriginalHost") {
            val host = ability.originalHost
            return if (host.canTap()) PaymentDecision.card(host) else null
        }

        var sameType = false
        if (type.contains(".sharesCreatureTypeWith")) {
            sameType = true
            type = TextUtil.fastReplace(type, ".sharesCreatureTypeWith", "")
        }
        var totalPower = false
        var totalP = ""
        if (type.contains("+withTotalPowerGE")) {
            totalPower = true
            totalP = type.split("withTotalPowerGE")[1]
            type = TextUtil.fastReplace(type, "+withTotalPowerGE$totalP", "")
        }

        var typeList =
            CardLists.getValidCards(
                player.getCardsIn(ZoneType.Battlefield),
                type.split(";").toTypedArray(),
                player,
                source,
                ability,
            )
        typeList = CardLists.filter(typeList, if (ability.isCrew) CardPredicates.CAN_CREW else CardPredicates.CAN_TAP)

        var c: Int? = null
        if (amount != "Any") {
            c = cost.getAbilityAmount(ability)
        }
        if (c != null && c == 0) return PaymentDecision.number(0)

        if (sameType) {
            val list2 = typeList
            typeList =
                CardLists.filter(typeList) { c12 ->
                    list2.any { card -> card != c12 && card.sharesCreatureTypeWith(c12) }
                }
            val tapped = CardCollection()
            var remaining = c ?: return null
            while (remaining > 0) {
                val selected =
                    selectCards(
                        Localizer.getInstance().getMessage("lblSelectOneOfCardsToTapAlreadyChosen", tapped),
                        typeList,
                        1,
                        1,
                        cancelAllowed = true,
                    ) ?: return null
                val first = selected.first()
                tapped.add(first)
                typeList = CardLists.filter(typeList) { it.sharesCreatureTypeWith(first) }
                typeList.remove(first)
                remaining--
            }
            return PaymentDecision.card(tapped)
        }

        if (totalPower) {
            return selectTotalPowerTapCost(cost, typeList, totalP.toInt())
        }

        if (c != null && c > typeList.size) {
            if (!isEffect) {
                controller.gui.message(
                    Localizer.getInstance().getMessage("lblEnoughValidCardNotToPayTheCost"),
                    Localizer.getInstance().getMessage("lblCostPaymentInvalid"),
                )
            }
            return null
        }

        val minSelection = c ?: 1
        val maxSelection = c ?: typeList.size
        val plan =
            CostDecisionPlanner.tapTypePlan(
                minSelection = minSelection,
                maxSelection = maxSelection,
                isStation = ability.isKeyword(Keyword.STATION),
            )
        val selected =
            selectCards(
                Localizer.getInstance().getMessage("lblSelectATargetToTap", cost.descriptiveType, "%d"),
                typeList,
                minSelection,
                maxSelection,
                cancelAllowed = !mandatory,
                semantic = plan.toCardSelectionPlan().semantic,
            ) ?: return null
        return PaymentDecision.card(selected)
    }

    override fun visit(cost: CostPutCardToLib): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        val list =
            CardLists.getValidCards(
                if (cost.sameZone) player.game.getCardsIn(cost.from) else player.getCardsIn(cost.from),
                cost.type.split(";").toTypedArray(),
                player,
                source,
                ability,
            )

        if (cost.payCostFromSource()) {
            return if (source.zone == player.getZone(cost.from) &&
                confirmAction(Localizer.getInstance().getMessage("lblPutCardToLibraryConfirm", source.translatedName))
            ) {
                PaymentDecision.card(source)
            } else {
                null
            }
        }

        if (cost.from == ZoneType.Hand) {
            val selected =
                selectCards(
                    Localizer.getInstance().getMessage("lblPutNCardsFromYourZone", "%d", cost.from.translatedName),
                    list,
                    c,
                    c,
                    cancelAllowed = true,
                ) ?: return null
            return PaymentDecision.card(selected)
        }

        if (cost.sameZone) {
            val players = player.game.players
            val payableZone = mutableListOf<Player>()
            for (p in players) {
                val enoughType = CardLists.filter(list, CardPredicates.isOwner(p))
                if (enoughType.size < c) {
                    list.removeAll(enoughType)
                } else {
                    payableZone.add(p)
                }
            }
            val gameCachePlayer: GameEntityViewMap<Player, PlayerView> = GameEntityView.getMap(payableZone)
            val pv =
                controller.gui.oneOrNone(
                    TextUtil.concatNoSpace(Localizer.getInstance().getMessage("lblPutCardsFromWhoseZone"), cost.from.translatedName),
                    gameCachePlayer.trackableKeys,
                )
            if (pv == null || !gameCachePlayer.containsKey(pv)) return null
            val p = gameCachePlayer[pv]
            val typeList = CardLists.filter(list, CardPredicates.isOwner(p))
            if (typeList.size < c) return null
            val chosen = CardCollection()
            val gameCacheCard: GameEntityViewMap<Card, CardView> = GameEntityView.getMap(typeList)
            repeat(c) {
                val cv =
                    controller.gui.oneOrNone(
                        Localizer.getInstance().getMessage("lblPutZoneCardsToLibrary", cost.from.translatedName),
                        gameCacheCard.trackableKeys,
                    )
                if (cv == null || !gameCacheCard.containsKey(cv)) return null
                chosen.add(gameCacheCard.remove(cv))
            }
            return PaymentDecision.card(chosen)
        }

        // From graveyard (non-same-zone)
        if (list.size < c) return null
        val chosen = CardCollection()
        val gameCacheCard: GameEntityViewMap<Card, CardView> = GameEntityView.getMap(list)
        repeat(c) {
            val cv =
                controller.gui.oneOrNone(
                    Localizer.getInstance().getMessage("lblFromZonePutToLibrary", cost.from.translatedName),
                    gameCacheCard.trackableKeys,
                )
            if (cv == null || !gameCacheCard.containsKey(cv)) return null
            chosen.add(gameCacheCard.remove(cv))
        }
        return PaymentDecision.card(chosen)
    }
}
