package leyline.bridge

import forge.card.mana.ManaAtom
import forge.card.mana.ManaCostShard
import forge.game.ability.AbilityUtils
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.card.CardLists
import forge.game.cost.CostSacrifice
import forge.game.keyword.Keyword
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType

/**
 * Generous upper bound on whether a player could pay an ability's mana cost.
 *
 * Each colour requirement is compared with every source that could produce that
 * colour, and the same source may count toward several colours. Sources are
 * floating mana that may pay this ability, mana abilities on the battlefield
 * and in hand (including paid filters and sacrifice outlets), Convoke creatures
 * and Improvise artifacts. The
 * total requirement is compared with the net mana those sources add. X counts as
 * zero, and Phyrexian mana counts as payable when the player can pay 2 life.
 *
 * A failed bound proves the cost unaffordable; a passing bound proves nothing.
 */
internal object ManaAffordabilityBound {
    private const val ALL_TYPES = ManaAtom.ALL_MANA_TYPES.toInt()

    private data class Source(
        val types: Int,
        val amount: Int,
    )

    fun allows(
        sa: SpellAbility,
        player: Player,
    ): Boolean {
        val cost = ActionManaCosts.computeEffectiveCost(sa, player) ?: return true
        val sources = sources(sa, player)
        var required = cost.genericCost
        val requiredByTypes = mutableMapOf<Int, Int>()
        for (shard in cost) {
            when {
                shard == ManaCostShard.X -> Unit
                shard.isPhyrexian && player.canPayLife(2, false, sa) -> Unit
                shard.isGeneric -> required++
                else -> {
                    required++
                    requiredByTypes.merge(shardTypes(shard), 1, Int::plus)
                }
            }
        }
        return required <= sources.sumOf { it.amount } &&
            requiredByTypes.all { (types, count) ->
                count <= sources.filter { it.types and types != 0 }.sumOf { maxOf(it.amount, 1) }
            }
    }

    private fun shardTypes(shard: ManaCostShard): Int = shard.colorMask.toInt() or (if (shard.isColorless) ManaAtom.COLORLESS else 0)

    private fun sources(
        sa: SpellAbility,
        player: Player,
    ): List<Source> =
        buildList {
            for (mana in player.manaPool) {
                if (mana.meetsManaRestrictions(sa)) add(Source(mana.color.toInt().takeIf { it != 0 } ?: ManaAtom.COLORLESS, 1))
            }
            for (card in player.getCardsIn(ZoneType.Battlefield) + player.getCardsIn(ZoneType.Hand)) {
                addAll(manaAbilitySources(card, sa, player))
            }
            val host = sa.hostCard
            val untapped = player.getCardsIn(ZoneType.Battlefield).filter { !it.isTapped }
            if (host?.hasKeyword(Keyword.CONVOKE) == true) {
                untapped.filter(Card::isCreature).forEach { add(Source(it.color.color.toInt(), 1)) }
            }
            if (host?.hasKeyword(Keyword.IMPROVISE) == true) {
                untapped.filter(Card::isArtifact).forEach { add(Source(0, 1)) }
            }
        }

    private fun manaAbilitySources(
        card: Card,
        paying: SpellAbility,
        player: Player,
    ): List<Source> =
        getPlayableManaAbilities(card, player).mapNotNull { ability ->
            val mana = ability.manaPart ?: return@mapNotNull null
            val cost = ability.payCosts
            if (card.isTapped && cost?.hasTapCost() == true) return@mapNotNull null
            if (!mana.meetsManaRestrictions(paying)) return@mapNotNull null
            val produced = if (mana.isComboMana) mana.getComboColors(ability) else mana.origProduced
            val types =
                if (ability.api == ApiType.ManaReflected || mana.isAnyMana || "Chosen" in produced) {
                    ALL_TYPES
                } else {
                    produced.split(" ").fold(0) { acc, token -> acc or ManaAtom.fromName(token).toInt() }.takeIf { it != 0 } ?: ALL_TYPES
                }
            val perActivation =
                (if (mana.isComboMana || mana.isAnyMana) 1 else produced.split(" ").count { it.isNotBlank() }) *
                    AbilityUtils.calculateAmount(card, ability.getParamOrDefault("Amount", "1"), ability)
            val activationMana = cost?.totalMana?.cmc ?: 0
            Source(types, (perActivation - activationMana).coerceAtLeast(0) * activations(card, ability, player))
        }

    /** Untapped repeatable sacrifice outlets may activate once per permanent they can sacrifice. */
    private fun activations(
        card: Card,
        ability: SpellAbility,
        player: Player,
    ): Int {
        val cost = ability.payCosts ?: return 1
        if (cost.hasTapCost()) return 1
        val sacrifice = cost.costParts.filterIsInstance<CostSacrifice>().singleOrNull() ?: return 1
        if (sacrifice.payCostFromSource()) return 1
        return CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield), sacrifice.type, player, card, ability).size
    }
}
