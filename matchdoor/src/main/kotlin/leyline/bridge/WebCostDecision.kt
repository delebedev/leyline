package leyline.bridge

import com.google.common.collect.ImmutableList
import com.google.common.collect.Lists
import forge.card.CardType
import forge.card.ColorSet
import forge.card.MagicColor
import forge.game.GameEntityCounterTable
import forge.game.GameEntityView
import forge.game.GameEntityViewMap
import forge.game.ability.AbilityUtils
import forge.game.card.*
import forge.game.cost.*
import forge.game.player.Player
import forge.game.player.PlayerView
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import forge.player.PlayerControllerHuman
import forge.util.*
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Web-based cost decision maker: routes interactive cost choices through the
 * [InteractivePromptBridge] instead of desktop Input* classes.
 *
 * Extends [CostDecisionMakerBase] and implements all ~35 [ICostVisitor] methods.
 * Non-interactive costs (confirm-only) go through [WebGuiGame.confirm];
 * interactive card selections go through the [selectCards] helper.
 *
 * See ADR-010 Seam 1 spike for design rationale.
 */
class WebCostDecision(
    private val controller: PlayerControllerHuman,
    p: Player,
    sa: SpellAbility,
    effect: Boolean,
    private val bridge: InteractivePromptBridge,
    source: Card = sa.hostCard,
    private val orString: String? = null,
) : CostDecisionMakerBase(p, effect, sa, source) {

    companion object {
        @Suppress("UnusedPrivateProperty")
        private val log = LoggerFactory.getLogger(WebCostDecision::class.java)
    }

    private var mandatory: Boolean = sa.payCosts?.isMandatory ?: false

    override fun paysRightAfterDecision(): Boolean = true

    // ═══════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════

    private fun confirmAction(cost: CostPart, message: String): Boolean {
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
    ): CardCollection? {
        if (cards.isEmpty()) return if (cancelAllowed) null else CardCollection()
        if (cards.size <= min && !cancelAllowed) {
            return CardCollection(cards)
        }
        val labels = cards.map { it.name }
        val refs = cards.mapIndexed { idx, card ->
            PromptCandidateRefDto(idx, "card", card.id, card.zone?.zoneType?.name)
        }
        val request = PromptRequest(
            promptType = "choose_cards",
            message = message,
            options = labels,
            min = min,
            max = max,
            defaultIndex = 0,
            candidateRefs = refs,
        )
        val indices = bridge.requestChoice(request)
        if (indices.isEmpty() && cancelAllowed) return null
        val selected = CardCollection()
        for (idx in indices) {
            if (idx in 0 until cards.size) selected.add(cards[idx])
        }
        return if (selected.size >= min) selected else null
    }

    // ═══════════════════════════════════════════════════════════════════
    // Non-interactive visit() methods
    // ═══════════════════════════════════════════════════════════════════

    override fun visit(cost: CostAddMana): PaymentDecision =
        PaymentDecision.number(cost.getAbilityAmount(ability))

    override fun visit(cost: CostChooseColor): PaymentDecision {
        val c = cost.getAbilityAmount(ability)
        return PaymentDecision.colors(
            player.controller.chooseColors(
                Localizer.getInstance().getMessage("lblChooseAColor"),
                ability,
                c,
                c,
                ColorSet.WUBRG,
            ),
        )
    }

    override fun visit(cost: CostChooseCreatureType): PaymentDecision? {
        val choice = controller.chooseSomeType(
            Localizer.getInstance().getMessage("lblCreature"),
            ability,
            CardType.getAllCreatureTypes(),
            true,
        ) ?: return null
        return PaymentDecision.type(choice)
    }

    override fun visit(cost: CostDamage): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        return if (confirmAction(cost, Localizer.getInstance().getMessage("lblDoYouWantCardDealNDamageToYou", source.translatedName, c.toString()))) {
            PaymentDecision.number(c)
        } else {
            null
        }
    }

    override fun visit(cost: CostDraw): PaymentDecision? {
        if (!cost.canPay(ability, player, isEffect)) return null
        val c = cost.getAbilityAmount(ability)
        val res = cost.getPotentialPlayers(player, ability)
        val message = if (!orString.isNullOrEmpty()) {
            if (res.contains(player)) {
                Localizer.getInstance().getMessage("lblDoYouWantLetThatPlayerDrawNCardOrDoAction", c.toString(), orString)
            } else {
                Localizer.getInstance().getMessage("lblDoYouWantDrawNCardOrDoAction", c.toString(), orString)
            }
        } else {
            Localizer.getInstance().getMessage("lblDrawNCardsConfirm", c.toString())
        }
        if (!confirmAction(cost, message)) return null
        val decision = PaymentDecision.players(res)
        decision.c = c
        return decision
    }

    override fun visit(cost: CostFlipCoin): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        return if (confirmAction(cost, Localizer.getInstance().getMessage("lblDoYouWantFlipNCoinAction", c.toString()))) {
            PaymentDecision.number(c)
        } else {
            null
        }
    }

    override fun visit(cost: CostRollDice): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        return if (confirmAction(cost, Localizer.getInstance().getMessage("lblDoYouWantRollNDiceAction", c.toString(), "d${cost.type}"))) {
            PaymentDecision.number(c)
        } else {
            null
        }
    }

    override fun visit(cost: CostMill): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        val message = if (!orString.isNullOrEmpty()) {
            Localizer.getInstance().getMessage("lblDoYouWantMillNCardsOrDoAction", c.toString(), orString)
        } else {
            Localizer.getInstance().getMessage("lblMillNCardsFromYourLibraryConfirm", c.toString())
        }
        return if (confirmAction(cost, message)) PaymentDecision.number(c) else null
    }

    override fun visit(cost: CostPayLife): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        if (mandatory) return PaymentDecision.number(c)
        val message = if (!orString.isNullOrEmpty()) {
            Localizer.getInstance().getMessage("lblDoYouWantPayNLife", c.toString(), orString)
        } else {
            Localizer.getInstance().getMessage("lblPayNLifeConfirm", c.toString())
        }
        if (player.canPayLife(c, isEffect, ability) && confirmAction(cost, message)) {
            if (!player.game.EXPERIMENTAL_RESTORE_SNAPSHOT) {
                mandatory = true
            }
            return PaymentDecision.number(c)
        }
        return null
    }

    override fun visit(cost: CostPayEnergy): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        if (player.canPayEnergy(c) &&
            confirmAction(cost, Localizer.getInstance().getMessage("lblPayEnergyConfirm", cost.toString(), player.getCounters(CounterEnumType.ENERGY).toString(), "{E}"))
        ) {
            return PaymentDecision.number(c)
        }
        return null
    }

    override fun visit(cost: CostPayShards): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        if (player.canPayShards(c) &&
            confirmAction(cost, Localizer.getInstance().getMessage("lblPayShardsConfirm", cost.toString(), player.numManaShards.toString(), "{M} (Mana Shards)"))
        ) {
            return PaymentDecision.number(c)
        }
        return null
    }

    override fun visit(cost: CostPartMana): PaymentDecision = PaymentDecision(0)

    override fun visit(cost: CostTap): PaymentDecision = PaymentDecision.number(1)

    override fun visit(cost: CostUntap): PaymentDecision = PaymentDecision.number(1)

    override fun visit(cost: CostRevealChosen): PaymentDecision = PaymentDecision.number(1)

    override fun visit(cost: CostPromiseGift): PaymentDecision? {
        val opponents = cost.getPotentialPlayers(player, ability)
        val giftee = controller.chooseSingleEntityForEffect(
            opponents,
            null,
            ability,
            "Choose an opponent to promise a gift",
            false,
            null,
            null,
        ) ?: return null
        return PaymentDecision.players(Lists.newArrayList(giftee))
    }

    override fun visit(cost: CostGainLife): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        val oppsThatCanGainLife = cost.getPotentialTargets(player, ability).filter { it.canGainLife() }
        if (cost.cntPlayers == Int.MAX_VALUE) {
            return PaymentDecision.players(oppsThatCanGainLife)
        }
        val gameCachePlayer: GameEntityViewMap<Player, PlayerView> = GameEntityView.getMap(oppsThatCanGainLife)
        val pv = controller.gui.oneOrNone(
            Localizer.getInstance().getMessage("lblCardChooseAnOpponentToGainNLife", source.translatedName, c.toString()),
            gameCachePlayer.trackableKeys,
        )
        if (pv == null || !gameCachePlayer.containsKey(pv)) return null
        return PaymentDecision.players(Lists.newArrayList(gameCachePlayer[pv]))
    }

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
        for (i in 0 until c) {
            val o = controller.gui.oneOrNone(
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

    override fun visit(cost: CostExiledMoveToGrave): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        val activator = ability.activatingPlayer
        val list = CardLists.getValidCards(
            activator.game.getCardsIn(ZoneType.Exile),
            cost.type.split(";").toTypedArray(),
            activator,
            source,
            ability,
        )
        if (list.size < c) return null
        val min = if (ability.isOptionalTrigger) 0 else c
        val gameCacheExile: GameEntityViewMap<Card, CardView> = GameEntityView.getMap(list)
        val views = controller.gui.many(
            Localizer.getInstance().getMessage("lblChooseAnExiledCardPutIntoGraveyard"),
            Localizer.getInstance().getMessage("lblToGraveyard"),
            min,
            c,
            CardView.getCollection(list),
            CardView.get(source),
        )
        if (views == null || views.size < c) return null
        val result = Lists.newArrayList<Card>()
        gameCacheExile.addToList(views, result)
        return PaymentDecision.card(result)
    }

    override fun visit(cost: CostBlight): PaymentDecision? = visit(cost as CostPutCounter)

    override fun visit(cost: CostBeholdExile): PaymentDecision? = visit(cost as CostBehold)

    // ═══════════════════════════════════════════════════════════════════
    // Interactive visit() methods (bridge-based card selection)
    // ═══════════════════════════════════════════════════════════════════

    override fun visit(cost: CostCollectEvidence): PaymentDecision? {
        val list = CardLists.filter(
            player.getCardsIn(ZoneType.Graveyard),
            CardPredicates.canExiledBy(ability, isEffect),
        )
        val total = AbilityUtils.calculateAmount(source, cost.amount, ability)
        val selected = selectCards(
            Localizer.getInstance().getMessage("lblCollectEvidence", total),
            list,
            0,
            list.size,
            cancelAllowed = true,
        ) ?: return null
        if (CardLists.getTotalCMC(selected) < total) return null
        return PaymentDecision.card(selected)
    }

    override fun visit(cost: CostDiscard): PaymentDecision? {
        var hand: CardCollectionView = player.getCardsIn(ZoneType.Hand)
        val discardType = cost.type

        if (cost.payCostFromSource()) {
            return if (hand.contains(source)) PaymentDecision.card(source) else null
        }
        if (discardType == "Hand") {
            if (!mandatory && !confirmAction(cost, Localizer.getInstance().getMessage("lblDoYouWantDiscardYourHand"))) {
                return null
            }
            if (hand.size > 1 && ability.activatingPlayer != null) {
                hand = ability.activatingPlayer.controller.orderMoveToZoneList(hand, ZoneType.Graveyard, ability)
            }
            return PaymentDecision.card(hand)
        }
        if (discardType == "LastDrawn") {
            val lastDrawn = player.lastDrawnCard
            return if (hand.contains(lastDrawn)) PaymentDecision.card(lastDrawn) else null
        }

        var c = cost.getAbilityAmount(ability)

        if (discardType == "Random") {
            var randomSubset: CardCollectionView = CardCollection(Aggregates.random(hand, c))
            if (randomSubset.size > 1 && ability.activatingPlayer != null) {
                randomSubset = ability.activatingPlayer.controller.orderMoveToZoneList(randomSubset, ZoneType.Graveyard, ability)
            }
            return PaymentDecision.card(randomSubset)
        }
        if (discardType.contains("+WithDifferentNames")) {
            val discarded = CardCollection()
            while (c > 0) {
                val selected = selectCards(
                    Localizer.getInstance().getMessage("lblSelectOneDifferentNameCardToDiscardAlreadyChosen") + discarded,
                    hand,
                    1,
                    1,
                    cancelAllowed = true,
                ) ?: return null
                val first = selected.first
                discarded.add(first)
                hand = CardLists.filter(hand, CardPredicates.sharesNameWith(first).negate())
                c--
            }
            return PaymentDecision.card(discarded)
        }
        if (discardType.contains("+WithSameName")) {
            val type = TextUtil.fastReplace(discardType, "+WithSameName", "")
            hand = CardLists.getValidCards(hand, type.split(";").toTypedArray(), player, source, ability)
            val hand2 = hand
            hand = CardLists.filter(hand) { c1 ->
                hand2.any { card -> card != c1 && card.name == c1.name }
            }
            if (c == 0) return PaymentDecision.card(CardCollection())
            val discarded = CardCollection()
            while (c > 0) {
                val selected = selectCards(
                    Localizer.getInstance().getMessage("lblSelectOneSameNameCardToDiscardAlreadyChosen") + discarded,
                    hand,
                    1,
                    1,
                    cancelAllowed = true,
                ) ?: return null
                val first = selected.first
                discarded.add(first)
                hand = CardLists.filter(hand, CardPredicates.nameEquals(first.name))
                (hand as CardCollection).remove(first)
                c--
            }
            return PaymentDecision.card(discarded)
        }

        // Typed discard
        val validType = discardType.split(";").toTypedArray()
        hand = CardLists.getValidCards(hand, validType, player, source, ability)
        if (hand.size < 1) return null
        val selected = selectCards(
            Localizer.getInstance().getMessage("lblSelectNMoreTargetTypeCardToDiscard", "%d", cost.descriptiveType),
            hand,
            c,
            c,
            cancelAllowed = !mandatory,
        ) ?: return null
        if (selected.size != c) return null
        return PaymentDecision.card(selected)
    }

    override fun visit(cost: CostExile): PaymentDecision? {
        var type = cost.type
        var onlyPayable: Card? = null
        if (cost.payCostFromSource()) onlyPayable = source
        if (type == "OriginalHost") onlyPayable = ability.originalHost

        if (onlyPayable != null) {
            if (onlyPayable.canExiledBy(ability, isEffect) &&
                onlyPayable.zone == player.getZone(cost.from[0]) &&
                confirmAction(cost, Localizer.getInstance().getMessage("lblExileConfirm", onlyPayable.translatedName))
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
        var list: CardCollection = if (cost.zoneRestriction != 1) {
            CardCollection(game.getCardsIn(cost.from))
        } else {
            CardCollection(player.getCardsIn(cost.from))
        }

        if (type == "All") {
            return if (confirmAction(cost, Localizer.getInstance().getMessage("lblExileNCardsFromYourZone", list.size, cost.from[0].translatedName))) {
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
            val selected = selectCards(
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
            val selected = selectCards(
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
            val selected = selectCards(
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
                val selected = selectCards(
                    Localizer.getInstance().getMessage("lblExileNCardsFromYourZone", "%d", fromZone.translatedName),
                    list,
                    c,
                    c,
                    cancelAllowed = !mandatory,
                ) ?: return null
                return PaymentDecision.card(selected)
            }
            if (fromZone == ZoneType.Library) {
                return if (confirmAction(cost, Localizer.getInstance().getMessage("lblExileNCardFromYourTopLibraryConfirm"))) {
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
                return if (confirmAction(cost, Localizer.getInstance().getMessage("lblExileConfirm", list.first.translatedName))) {
                    PaymentDecision.card(list.first)
                } else {
                    null
                }
            }
            val origin = Lists.newArrayList(cost.from)
            val required = if (sharedType) " (must share a card type)" else ""
            val chosen = controller.chooseCardsForZoneChange(
                ZoneType.Exile, origin, ability, list,
                if (mandatory) c else 0, c, null, cost.toString(c) + required, null,
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

    private fun exileFromSame(cost: CostExile, list: CardCollectionView, nNeeded: Int, payableZone: List<Player>): PaymentDecision? {
        if (nNeeded == 0) return PaymentDecision.number(0)
        val gameCachePlayer: GameEntityViewMap<Player, PlayerView> = GameEntityView.getMap(payableZone)
        val pv = controller.gui.oneOrNone(
            Localizer.getInstance().getMessage("lblExileFromWhoseZone", cost.from[0].translatedName),
            gameCachePlayer.trackableKeys,
        )
        if (pv == null || !gameCachePlayer.containsKey(pv)) return null
        val p = gameCachePlayer[pv]
        val typeList = CardLists.filter(list, CardPredicates.isOwner(p))
        if (typeList.size < nNeeded) return null
        val gameCacheExile: GameEntityViewMap<Card, CardView> = GameEntityView.getMap(typeList)
        val views = controller.gui.many(
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

    override fun visit(cost: CostExert): PaymentDecision? {
        val list = CardLists.getValidCards(
            player.getCardsIn(ZoneType.Battlefield),
            cost.type.split(";").toTypedArray(),
            player,
            source,
            ability,
        )
        if (cost.payCostFromSource()) {
            return if (source.controller == ability.activatingPlayer &&
                source.isInPlay &&
                confirmAction(cost, Localizer.getInstance().getMessage("lblExertCardConfirm", source.translatedName))
            ) {
                PaymentDecision.card(source)
            } else {
                null
            }
        }
        val c = cost.getAbilityAmount(ability)
        if (c == 0) return PaymentDecision.number(0)
        if (list.size < c) return null
        val selected = selectCards(
            Localizer.getInstance().getMessage("lblSelectACostToExert", cost.descriptiveType, "%d"),
            list,
            c,
            c,
            cancelAllowed = true,
        ) ?: return null
        return PaymentDecision.card(selected)
    }

    override fun visit(cost: CostEnlist): PaymentDecision? {
        val list = CostEnlist.getCardsForEnlisting(player)
        if (list.isEmpty()) return null
        val selected = selectCards(
            Localizer.getInstance().getMessage("lblSelectACostToEnlist", cost.descriptiveType, "%d"),
            list,
            1,
            1,
            cancelAllowed = true,
        ) ?: return null
        return PaymentDecision.card(selected)
    }

    override fun visit(cost: CostForage): PaymentDecision? {
        val food = CardLists.filter(
            player.getCardsIn(ZoneType.Battlefield),
            CardPredicates.isType("Food"),
            CardPredicates.canBeSacrificedBy(ability, isEffect),
        )
        val exile = CardLists.filter(
            player.getCardsIn(ZoneType.Graveyard),
            CardPredicates.canExiledBy(ability, isEffect),
        )
        if (!food.isEmpty() && confirmAction(cost, "Sacrifice Food")) {
            val selected = selectCards(
                Localizer.getInstance().getMessage("lblSelectATargetToSacrifice", "Food", "%d"),
                food,
                1,
                1,
                cancelAllowed = !mandatory,
            ) ?: return null
            return PaymentDecision.card(selected)
        }
        if (exile.size >= 3) {
            val selected = selectCards(
                Localizer.getInstance().getMessage("lblSelectToExile", 3),
                exile,
                3,
                3,
                cancelAllowed = !mandatory,
            ) ?: return null
            return PaymentDecision.card(selected)
        }
        return null
    }

    override fun visit(cost: CostGainControl): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        var validCards: CardCollectionView = CardLists.getValidCards(
            player.getCardsIn(ZoneType.Battlefield),
            cost.type.split(";").toTypedArray(),
            player,
            source,
            ability,
        )
        validCards = CardLists.filter(validCards) { crd -> crd.canBeControlledBy(player) }
        val selected = selectCards(
            Localizer.getInstance().getMessage(
                "lblGainNTargetControl",
                "%d",
                cost.typeDescription ?: cost.type,
            ),
            validCards,
            c,
            c,
            cancelAllowed = true,
        ) ?: return null
        return PaymentDecision.card(selected)
    }

    override fun visit(cost: CostPutCounter): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        if (cost.payCostFromSource()) {
            if (ability.hasParam("UnlessCost") &&
                !confirmAction(
                    cost,
                    Localizer.getInstance().getMessage("lblPutNTypeCounterOnTarget", c.toString(), cost.counter.name, ability.hostCard.displayName),
                )
            ) {
                return null
            }
            return PaymentDecision.card(source)
        }
        var typeList: CardCollectionView = CardLists.getValidCards(
            source.game.getCardsIn(ZoneType.Battlefield),
            cost.type.split(";").toTypedArray(),
            player,
            ability.hostCard,
            ability,
        )
        typeList = CardLists.filter(typeList, CardPredicates.canReceiveCounters(cost.counter))
        if (typeList.isEmpty()) return null
        val selected = selectCards(
            Localizer.getInstance().getMessage("lblPutNTypeCounterOnTarget", c.toString(), cost.counter.name, cost.descriptiveType),
            typeList,
            1,
            1,
            cancelAllowed = !mandatory,
        ) ?: return null
        return PaymentDecision.card(selected)
    }

    override fun visit(cost: CostReturn): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        if (cost.payCostFromSource()) {
            val card = ability.hostCard
            return if (card.controller == player &&
                card.isInPlay &&
                confirmAction(cost, Localizer.getInstance().getMessage("lblReturnCardToHandConfirm", CardTranslation.getTranslatedName(CardView.get(card).name)))
            ) {
                PaymentDecision.card(card)
            } else {
                null
            }
        }
        val validCards = CardLists.getValidCards(
            ability.activatingPlayer.getCardsIn(ZoneType.Battlefield),
            cost.type.split(";").toTypedArray(),
            player,
            source,
            ability,
        )
        if (validCards.size < c) return null
        val selected = selectCards(
            Localizer.getInstance().getMessage("lblNTypeCardsToHand", "%d", cost.descriptiveType),
            validCards,
            c,
            c,
            cancelAllowed = !mandatory,
        ) ?: return null
        return PaymentDecision.card(selected)
    }

    override fun visit(cost: CostReveal): PaymentDecision? {
        if (cost.payCostFromSource()) return PaymentDecision.card(source)
        if (cost.type == "Hand") return PaymentDecision.card(player.getCardsIn(ZoneType.Hand))

        if (cost.type == "SameColor") {
            val num = cost.getAbilityAmount(ability)
            var hand: CardCollectionView = player.getCardsIn(cost.revealFrom)
            val hand2 = hand
            hand = CardLists.filter(hand) { c ->
                hand2.any { card -> card != c && card.sharesColorWith(c) }
            }
            if (num == 0) return PaymentDecision.number(0)
            val selected = selectCards(
                Localizer.getInstance().getMessage("lblSelectNCardOfSameColorToReveal", num.toString()),
                hand,
                num,
                num,
                cancelAllowed = !mandatory,
            ) ?: return null
            // Validate all selected share a color
            if (selected.size > 1) {
                val first = selected.first
                if (!selected.all { it === first || CardPredicates.sharesColorWith(first).test(it) }) {
                    return null
                }
            }
            return PaymentDecision.card(selected)
        }

        // Standard typed reveal
        val num = cost.getAbilityAmount(ability)
        var hand = player.getCardsIn(cost.revealFrom)
        hand = CardLists.getValidCards(hand, cost.type.split(";").toTypedArray(), player, source, ability)
        if (hand.size < num) return null
        if (num == 0) return PaymentDecision.number(0)
        if (!ability.isCastFromPlayEffect && hand.size == num) return PaymentDecision.card(hand)
        val selected = selectCards(
            Localizer.getInstance().getMessage("lblSelectNMoreTypeCardsTpReveal", "%d", cost.descriptiveType),
            hand,
            num,
            num,
            cancelAllowed = !mandatory,
        ) ?: return null
        return PaymentDecision.card(selected)
    }

    override fun visit(cost: CostBehold): PaymentDecision? {
        val num = cost.getAbilityAmount(ability)
        var hand = player.getCardsIn(cost.revealFrom)
        hand = CardLists.getValidCards(hand, cost.type.split(";").toTypedArray(), player, source, ability)
        if (hand.size < num) return null
        val selected = selectCards(
            Localizer.getInstance().getMessage("lblSelectNMoreTypeCardsTpReveal", "%d", cost.descriptiveType),
            hand,
            num,
            num,
            cancelAllowed = !mandatory,
        ) ?: return null
        return PaymentDecision.card(selected)
    }

    override fun visit(cost: CostRemoveAnyCounter): PaymentDecision? {
        var c = cost.getAbilityAmount(ability)
        var list: CardCollectionView = CardLists.getValidCards(
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
            val labels = list.map { card ->
                val counterStr = card.counters.entries.joinToString(", ") { "${it.key.name}: ${it.value}" }
                "${card.name} ($counterStr)"
            }
            val refs = list.mapIndexed { idx, card ->
                PromptCandidateRefDto(idx, "card", card.id, card.zone?.zoneType?.name)
            }
            val request = PromptRequest(
                promptType = "choose_cards",
                message = Localizer.getInstance().getMessage(
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

            val cType = if (cost.counter != null) {
                cost.counter
            } else {
                val cmap = counterTable.filterToRemove(card)
                if (cmap.size == 1) {
                    cmap.keys.first()
                } else {
                    val counterTypes = Lists.newArrayList(cmap.keys)
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
                val prompt = Localizer.getInstance().getMessage("lblRemoveAllCountersConfirm") +
                    if (anyCounters) "" else " (${cntrs!!.name})"
                if (!confirmAction(cost, prompt)) return null
                cntRemoved = maxCounters
            } else if (ability != null && !ability.isPwAbility) {
                if (maxCounters < cntRemoved) return null
                if (!confirmAction(
                        cost,
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

        var validCards: CardCollectionView = CardLists.getValidCards(
            player.getCardsIn(cost.zone),
            type.split(";").toTypedArray(),
            player,
            source,
            ability,
        )
        validCards = if (anyCounters) {
            CardLists.filterAnyCounters(validCards, cntRemoved)
        } else {
            CardLists.filter(validCards, CardPredicates.hasCounter(cntrs, cntRemoved))
        }
        if (validCards.isEmpty()) return null

        val selected = selectCards(
            Localizer.getInstance().getMessage(
                "lblRemoveCountersFromAInZoneCard",
                Lang.joinHomogenous(cost.zone) { z -> z.translatedName },
            ),
            validCards,
            1,
            1,
            cancelAllowed = true,
        )
        val card = selected?.first ?: return null

        val counterTable = generateCounterTable(card, cntrs, cntRemoved)
        return if (counterTable.isEmpty) null else PaymentDecision.counters(counterTable)
    }

    private fun generateCounterTable(c: Card, cType: CounterType?, cntToRemove: Int): GameEntityCounterTable {
        val counterTable = GameEntityCounterTable()
        if (cType != null) {
            counterTable.put(null, c, cType, cntToRemove)
        } else {
            val cMap = counterTable.filterToRemove(c)
            for (ct in ImmutableList.copyOf(cMap.keys)) {
                if (!c.canRemoveCounters(ct)) cMap.remove(ct)
            }
            if (cMap.isEmpty()) return counterTable
            if (cMap.size == 1) {
                counterTable.put(null, c, cMap.entries.first().key, cntToRemove)
            } else {
                var remaining = cntToRemove
                while (remaining > 0) {
                    val pc = c.controller.controller
                    val chosen = pc.chooseCounterType(
                        Lists.newArrayList(cMap.keys),
                        ability,
                        Localizer.getInstance().getMessage("lblSelectCountersTypeToRemove"),
                        null,
                    ) ?: break
                    val max = remaining.coerceAtMost(cMap[chosen] ?: 0)
                    val totalRemaining = cMap.values.sum()
                    val min = 1.coerceAtLeast(max - totalRemaining)
                    val chosenAmount = pc.chooseNumber(
                        ability,
                        Localizer.getInstance().getMessage("lblSelectRemoveCountersNumberOfTarget", chosen.name),
                        min,
                        max,
                        null,
                    )
                    if (chosenAmount > 0) {
                        counterTable.put(null, c, chosen, chosenAmount)
                        @Suppress("UNUSED_VALUE")
                        cMap.putAll(counterTable.filterToRemove(c))
                    }
                    remaining -= chosenAmount
                }
            }
        }
        return counterTable
    }

    override fun visit(cost: CostSacrifice): PaymentDecision? {
        val amount = cost.amount
        var type = cost.type

        if (cost.payCostFromSource()) {
            return if (source.controller == ability.activatingPlayer &&
                source.canBeSacrificedBy(ability, isEffect) &&
                (mandatory || confirmAction(cost, Localizer.getInstance().getMessage("lblSacrificeCardConfirm", source.translatedName)))
            ) {
                PaymentDecision.card(source)
            } else {
                null
            }
        }
        if (type == "OriginalHost") {
            val host = ability.originalHost
            return if (host.controller == ability.activatingPlayer &&
                host.canBeSacrificedBy(ability, isEffect) &&
                confirmAction(cost, Localizer.getInstance().getMessage("lblSacrificeCardConfirm", host.translatedName))
            ) {
                PaymentDecision.card(host)
            } else {
                null
            }
        }

        var differentNames = false
        if (type.contains("+WithDifferentNames")) {
            type = type.replace("+WithDifferentNames", "")
            differentNames = true
        }

        var list: CardCollectionView = CardLists.filter(
            player.getCardsIn(ZoneType.Battlefield),
            CardPredicates.canBeSacrificedBy(ability, isEffect),
        )
        list = CardLists.getValidCards(list, type.split(";").toTypedArray(), player, source, ability)

        if (amount == "All") return PaymentDecision.card(list)

        var c = cost.getAbilityAmount(ability)
        if (c == 0) return PaymentDecision.number(0)

        if (differentNames) {
            val chosen = CardCollection()
            while (c > 0) {
                val selected = selectCards(
                    Localizer.getInstance().getMessage("lblSelectATargetToSacrifice", cost.descriptiveType, c),
                    list,
                    1,
                    1,
                    cancelAllowed = true,
                ) ?: return null
                val first = selected.first
                chosen.add(first)
                list = CardLists.filter(list, CardPredicates.sharesNameWith(first).negate())
                c--
            }
            return PaymentDecision.card(chosen)
        }

        if (list.size < c) return null
        val selected = selectCards(
            Localizer.getInstance().getMessage("lblSelectATargetToSacrifice", cost.descriptiveType, "%d"),
            list,
            c,
            c,
            cancelAllowed = !mandatory,
        ) ?: return null
        return PaymentDecision.card(selected)
    }

    override fun visit(cost: CostTapType): PaymentDecision? {
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

        var typeList = CardLists.getValidCards(
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
            typeList = CardLists.filter(typeList) { c12 ->
                list2.any { card -> card != c12 && card.sharesCreatureTypeWith(c12) }
            }
            val tapped = CardCollection()
            var remaining = c ?: return null
            while (remaining > 0) {
                val selected = selectCards(
                    Localizer.getInstance().getMessage("lblSelectOneOfCardsToTapAlreadyChosen", tapped),
                    typeList,
                    1,
                    1,
                    cancelAllowed = true,
                ) ?: return null
                val first = selected.first
                tapped.add(first)
                typeList = CardLists.filter(typeList) { it.sharesCreatureTypeWith(first) }
                typeList.remove(first)
                remaining--
            }
            return PaymentDecision.card(tapped)
        }

        if (totalPower) {
            val i = totalP.toInt()
            val selected = selectCards(
                Localizer.getInstance().getMessage("lblSelectACreatureToTap"),
                typeList,
                0,
                typeList.size,
                cancelAllowed = true,
            ) ?: return null
            if (CardLists.getTotalPower(selected, ability) < i) return null
            return PaymentDecision.card(selected)
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

        val selected = selectCards(
            Localizer.getInstance().getMessage("lblSelectATargetToTap", cost.descriptiveType, "%d"),
            typeList,
            c ?: 1,
            c ?: typeList.size,
            cancelAllowed = !mandatory,
        ) ?: return null
        return PaymentDecision.card(selected)
    }

    override fun visit(cost: CostUntapType): PaymentDecision? {
        var typeList = CardLists.getValidCards(
            player.game.getCardsIn(ZoneType.Battlefield),
            cost.type.split(";").toTypedArray(),
            player,
            source,
            ability,
        )
        typeList = CardLists.filter(typeList) { c ->
            c.canUntap(null, false) && (c.getCounters(CounterEnumType.STUN) == 0 || c.canRemoveCounters(CounterEnumType.STUN))
        }
        val c = cost.getAbilityAmount(ability)
        val selected = selectCards(
            Localizer.getInstance().getMessage("lblSelectATargetToUntap", cost.descriptiveType, "%d"),
            typeList,
            c,
            c,
            cancelAllowed = true,
        ) ?: return null
        if (selected.size != c) return null
        return PaymentDecision.card(selected)
    }

    override fun visit(cost: CostUnattach): PaymentDecision? {
        val cardToUnattach = cost.findCardToUnattach(source, player, ability)
        if (cardToUnattach.size == 1 && confirmAction(cost, Localizer.getInstance().getMessage("lblUnattachCardConfirm", cardToUnattach.first.translatedName))) {
            return PaymentDecision.card(cardToUnattach.first)
        }
        if (cardToUnattach.size > 1) {
            val c = cost.getAbilityAmount(ability)
            val selected = selectCards(
                Localizer.getInstance().getMessage("lblUnattachCardConfirm", cost.descriptiveType),
                cardToUnattach,
                c,
                c,
                cancelAllowed = true,
            ) ?: return null
            if (selected.size != c) return null
            return PaymentDecision.card(selected)
        }
        return null
    }

    override fun visit(cost: CostPutCardToLib): PaymentDecision? {
        val c = cost.getAbilityAmount(ability)
        val list = CardLists.getValidCards(
            if (cost.sameZone) player.game.getCardsIn(cost.from) else player.getCardsIn(cost.from),
            cost.type.split(";").toTypedArray(),
            player,
            source,
            ability,
        )

        if (cost.payCostFromSource()) {
            return if (source.zone == player.getZone(cost.from) &&
                confirmAction(cost, Localizer.getInstance().getMessage("lblPutCardToLibraryConfirm", source.translatedName))
            ) {
                PaymentDecision.card(source)
            } else {
                null
            }
        }

        if (cost.from == ZoneType.Hand) {
            val selected = selectCards(
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
            val pv = controller.gui.oneOrNone(
                TextUtil.concatNoSpace(Localizer.getInstance().getMessage("lblPutCardsFromWhoseZone"), cost.from.translatedName),
                gameCachePlayer.trackableKeys,
            )
            if (pv == null || !gameCachePlayer.containsKey(pv)) return null
            val p = gameCachePlayer[pv]
            val typeList = CardLists.filter(list, CardPredicates.isOwner(p))
            if (typeList.size < c) return null
            val chosen = CardCollection()
            val gameCacheCard: GameEntityViewMap<Card, CardView> = GameEntityView.getMap(typeList)
            for (i in 0 until c) {
                val cv = controller.gui.oneOrNone(
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
        for (i in 0 until c) {
            val cv = controller.gui.oneOrNone(
                Localizer.getInstance().getMessage("lblFromZonePutToLibrary", cost.from.translatedName),
                gameCacheCard.trackableKeys,
            )
            if (cv == null || !gameCacheCard.containsKey(cv)) return null
            chosen.add(gameCacheCard.remove(cv))
        }
        return PaymentDecision.card(chosen)
    }
}
