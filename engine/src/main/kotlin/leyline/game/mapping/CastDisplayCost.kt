package leyline.game.mapping

import forge.card.mana.ManaCost
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import leyline.game.ManaRequirementValue
import leyline.game.data.CardData
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaRequirement

/**
 * The single definition of an action offer's displayed mana cost.
 *
 * Displayed cost is the printed cost after every cost modification that
 * follows from game state alone, and before every reduction that requires
 * the player to choose which permanents or cards pay (Convoke, Improvise,
 * Delve, Waterbend, Offering, Emerge, Assist). The dividing line is the
 * choice, not the state read — see
 * docs/decisions/0007-displayed-cost-and-controller-contexts.md.
 *
 * [ActionManaCosts.computeEffectiveCost] enforces the rule by running Forge's
 * cost adjustment under a quiet [leyline.bridge.NonInteractiveScope], so no
 * keyword list is needed here. Affordability is a different question and must
 * not route through this object.
 */
internal object CastDisplayCost {
    /**
     * Displayed cost of [sa] for [player], or null when the ability carries
     * no mana cost.
     */
    fun of(
        sa: SpellAbility,
        player: Player,
    ): ManaCost? = ActionManaCosts.computeEffectiveCost(sa, player)

    /**
     * Displayed cost of [sa] as proto mana requirements, each echoing
     * [abilityGrpId] when set.
     *
     * Falls back to the ability's own printed cost when no effective cost is
     * computable, then to [printed] card data — so a null [sa] (no castable
     * ability resolved) still yields the printed cost of the card.
     */
    fun requirements(
        sa: SpellAbility?,
        player: Player,
        printed: CardData?,
        abilityGrpId: Int? = null,
    ): List<ManaRequirement> =
        requirementValues(sa, player, printed, abilityGrpId).map { value ->
            val req =
                ManaRequirement
                    .newBuilder()
                    .addAllColor(value.colors.mapNotNull(ManaColor::forNumber))
                    .setCount(value.count)
            if (value.abilityGrpId != 0) req.setAbilityGrpId(value.abilityGrpId)
            req.build()
        }

    fun requirementValues(
        sa: SpellAbility?,
        player: Player,
        printed: CardData?,
        abilityGrpId: Int? = null,
    ): List<ManaRequirementValue> {
        if (sa != null) {
            of(sa, player)?.let { return ActionManaCosts.forgeManaCostToValues(it, abilityGrpId) }
            val saCost = sa.payCosts?.totalMana
            if (saCost != null && !saCost.isNoCost) {
                return ActionManaCosts.forgeManaCostToValues(saCost, abilityGrpId)
            }
        }
        return printedRequirementValues(printed, abilityGrpId)
    }

    private fun printedRequirementValues(
        printed: CardData?,
        abilityGrpId: Int?,
    ): List<ManaRequirementValue> =
        printed?.manaCost.orEmpty().map { (color: ManaColor, count: Int) ->
            ManaRequirementValue(listOf(color.number), count, abilityGrpId ?: 0)
        }
}
