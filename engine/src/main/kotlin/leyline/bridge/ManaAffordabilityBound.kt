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
 * and Improvise artifacts. A card's tap abilities count once, as its best one, and
 * a paid ability counts only when other sources can pay its activation. The
 * total requirement is compared with the net mana those sources add. An announced
 * X is counted before cost reductions, as in payment; an unannounced X counts as
 * zero. Phyrexian mana counts as payable when the player can pay 2 life.
 *
 * A failed bound proves the cost unaffordable; a passing bound proves nothing.
 */
internal object ManaAffordabilityBound {
    private const val ALL_TYPES = ManaAtom.ALL_MANA_TYPES.toInt()

    /** [colored] is gross output for colour needs; [net] subtracts activation mana for the total. */
    private data class Source(
        val types: Int,
        val colored: Int,
        val net: Int = colored,
    )

    fun allows(
        sa: SpellAbility,
        player: Player,
    ): Boolean {
        val cost = ActionManaCosts.computeEffectiveCost(sa, player, expandAnnouncedX = true) ?: return true
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
        return required <= sources.sumOf { it.net } &&
            requiredByTypes.all { (types, count) ->
                count <= sources.filter { it.types and types != 0 }.sumOf { it.colored }
            }
    }

    private fun shardTypes(shard: ManaCostShard): Int = shard.colorMask.toInt() or (if (shard.isColorless) ManaAtom.COLORLESS else 0)

    private fun sources(
        sa: SpellAbility,
        player: Player,
    ): List<Source> =
        buildList {
            var floating = 0
            for (mana in player.manaPool) {
                floating++
                if (mana.meetsManaRestrictions(sa)) add(Source(mana.color.toInt().takeIf { it != 0 } ?: ManaAtom.COLORLESS, 1))
            }
            val options =
                (player.getCardsIn(ZoneType.Battlefield) + player.getCardsIn(ZoneType.Hand)).flatMap { manaAbilityOptions(it, sa, player) }
            addAll(usableUnits(options, floating))
            val host = sa.hostCard
            val untapped = player.getCardsIn(ZoneType.Battlefield).filter { !it.isTapped }
            if (host?.hasKeyword(Keyword.CONVOKE) == true) {
                untapped.filter(Card::isCreature).forEach { add(Source(it.color.color.toInt(), 1)) }
            }
            if (host?.hasKeyword(Keyword.IMPROVISE) == true) {
                untapped.filter(Card::isArtifact).forEach { add(Source(0, 1)) }
            }
        }

    /**
     * One way to use a mana ability. [unit] groups abilities that exclude each other:
     * every tap ability of a card shares the card's single untap, while other abilities
     * stand alone. [source] is what it adds toward this payment; [gross] is all the mana
     * it adds, which may pay another ability's activation even when restricted here.
     */
    private data class Option(
        val unit: Any,
        val source: Source,
        val gross: Int,
        val activationMana: Int,
    )

    /**
     * A paid ability, such as a filter, counts only when mana from other units, floating
     * mana included, can cover its activation; the check repeats so filters may feed each
     * other. Each unit then contributes its best usable option once.
     */
    private fun usableUnits(
        options: List<Option>,
        floating: Int,
    ): List<Source> {
        val byUnit = options.groupBy { it.unit }
        val usable = options.filter { it.activationMana == 0 }.toMutableSet()

        fun gross(unit: Any): Int = byUnit.getValue(unit).filter { it in usable }.maxOfOrNull { it.gross } ?: 0
        do {
            val added =
                options.filter { option ->
                    option !in usable &&
                        floating + byUnit.keys.filter { it != option.unit }.sumOf(::gross) >= option.activationMana
                }
            usable += added
        } while (added.isNotEmpty())
        return byUnit.values.mapNotNull { unitOptions ->
            // Mana this payment cannot spend adds no colour, though it may pay an activation.
            val live = unitOptions.filter { it in usable && it.source.colored > 0 }.map { it.source }
            if (live.isEmpty()) return@mapNotNull null
            Source(live.fold(0) { acc, source -> acc or source.types }, live.maxOf { it.colored }, live.maxOf { it.net })
        }
    }

    private fun manaAbilityOptions(
        card: Card,
        paying: SpellAbility,
        player: Player,
    ): List<Option> =
        getPlayableManaAbilities(card, player).mapNotNull { ability ->
            val mana = ability.manaPart ?: return@mapNotNull null
            val cost = ability.payCosts
            if (card.isTapped && cost?.hasTapCost() == true) return@mapNotNull null
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
            val count = activations(card, ability, player)
            val gross = perActivation.coerceAtLeast(1) * count
            val source =
                if (mana.meetsManaRestrictions(paying)) {
                    Source(types, colored = gross, net = (perActivation - activationMana).coerceAtLeast(0) * count)
                } else {
                    Source(0, colored = 0, net = 0)
                }
            // A card untaps once, so its tap abilities exclude each other.
            val unit: Any = if (cost?.hasTapCost() == true) card else ability
            Option(
                unit = unit,
                source = source,
                gross = gross,
                activationMana = activationMana,
            )
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
