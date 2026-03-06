package leyline.bridge

import forge.game.Game
import forge.game.card.CardLists
import forge.game.card.CardPredicates
import forge.game.player.Player
import forge.game.zone.ZoneType

object PlayableActionQuery {

    /**
     * Returns true if [player] has any meaningful (non-mana) action available
     * in the current game state. Used for smart phase skipping.
     */
    fun hasPlayableNonManaAction(game: Game, player: Player): Boolean {
        val handCards = player.getZone(ZoneType.Hand).cards
        val lands = CardLists.filter(handCards, CardPredicates.LANDS)
        for (card in lands) {
            val landAbility = forge.game.spellability.LandAbility(card, card.currentState)
            landAbility.activatingPlayer = player
            if (player.canPlayLand(card, false, landAbility)) return true
        }
        val nonLands = CardLists.filter(handCards, CardPredicates.NON_LANDS)
        for (card in nonLands) {
            if (chooseCastAbility(card, player) != null) return true
        }
        for (card in player.getZone(ZoneType.Battlefield).cards) {
            for (sa in getNonManaActivatedAbilities(card, player)) {
                if (sa.canPlay()) return true
            }
        }
        for (card in game.getCardsIn(listOf(ZoneType.Exile, ZoneType.Graveyard, ZoneType.Command))) {
            if (card.mayPlay(player).isEmpty()) continue
            if (chooseCastAbility(card, player) != null) return true
        }
        return false
    }
}
