package leyline.bridge

import forge.game.ability.ApiType
import forge.game.keyword.Keyword
import forge.game.player.Player
import forge.game.spellability.AbilityStatic
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType

/** Forge owns eligibility, timing and payment; these predicates identify its companion rail. */
object CompanionAction {
    const val ABILITY_GRP_ID = 202
    const val DESIGNATION_TYPE = 7

    fun matches(ability: SpellAbility): Boolean =
        ability is AbilityStatic &&
            ability.api == ApiType.ChangeZone &&
            ability.hostCard.hasKeyword(Keyword.COMPANION) &&
            ability.getParam("Origin") == "Command" &&
            ability.getParam("Destination") == "Hand"

    fun chosenCard(player: Player) =
        player
            .getCardsIn(ZoneType.Command)
            .firstOrNull { effect ->
                effect.effectSource?.hasKeyword(Keyword.COMPANION) == true &&
                    effect.staticAbilities.any { it.hasSVar("MoveToHand") }
            }?.effectSource
}
