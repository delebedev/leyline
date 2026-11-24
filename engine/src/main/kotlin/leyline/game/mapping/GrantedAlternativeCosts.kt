package leyline.game.mapping

import forge.game.card.Card
import forge.game.cost.Cost
import forge.game.player.Player
import forge.game.spellability.OptionalCost
import forge.game.spellability.SpellAbility
import forge.game.staticability.StaticAbility
import forge.game.staticability.StaticAbilityMode
import forge.game.zone.ZoneType
import leyline.game.state.GameBridge

/**
 * Spell alternative costs granted by another object's `AlternativeCost` static,
 * such as Dream Halls or Jodah, Archmage Eternal.
 *
 * Forge substitutes the static's cost into a copy of the spell's basic ability
 * and keeps no reference to the static. The granting static is recovered from
 * its applicability and the substituted cost. Its ability row on the granting
 * object identifies the cost branch as `alternativeGrpId`.
 */
internal object GrantedAlternativeCosts {
    /** The granting static's ability row, or null when [sa] is not such an alternative cast. */
    fun abilityGrpId(
        sa: SpellAbility,
        player: Player,
        bridge: GameBridge,
    ): Int? {
        val static = grantingStatic(sa, player) ?: return null
        val host = static.hostCard
        val registry = bridge.abilityRegistryFor(host, bridge.cardRepository.findByGrpId(bridge.resolveGrpId(host)))
        return registry?.forStaticAbility(static.definitionId)?.takeIf { it > 0 }
    }

    /** The static on another object whose cost Forge substituted into [sa]. */
    fun grantingStatic(
        sa: SpellAbility,
        player: Player,
    ): StaticAbility? {
        if (!sa.isSpell || sa.isBasicSpell || sa.alternativeCost != null || sa.isOptionalCostPaid(OptionalCost.AltCost)) {
            return null
        }
        val card = sa.hostCard ?: return null
        val paid = sa.payCosts?.toSimpleString() ?: return null
        val basicSpells = card.spells.filter { it.isBasicSpell }
        if (basicSpells.isEmpty()) return null
        return card.game
            .getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)
            .asSequence()
            .filter { it != card }
            .flatMap { it.staticAbilities.orEmpty().asSequence() }
            .filter { it.checkMode(StaticAbilityMode.AlternativeCost) }
            .firstOrNull { static ->
                basicSpells.any { basic -> applies(static, basic, card, player) && substitutedCost(static, basic, card) == paid }
            }
    }

    private fun applies(
        static: StaticAbility,
        basic: SpellAbility,
        card: Card,
        player: Player,
    ): Boolean =
        static.matchesValidParam("ValidSA", basic) &&
            static.matchesValidParam("ValidCard", card) &&
            static.matchesValidParam("ValidPlayer", player) &&
            static.checkConditions()

    private fun substitutedCost(
        static: StaticAbility,
        basic: SpellAbility,
        card: Card,
    ): String? {
        val template = static.getParam("Cost")?.replace("ConvertedManaCost", card.cmc.toString()) ?: return null
        val cost = basic.payCosts?.copyWithNoMana() ?: return null
        cost.add(Cost(template, false))
        return cost.toSimpleString()
    }
}
