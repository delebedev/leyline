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
        // Hand cards: activated abilities with non-battlefield activation zones (Channel)
        for (card in handCards) {
            for (sa in getNonManaActivatedAbilities(card, player)) {
                if (sa.canPlay()) return true
            }
        }
        // Zone casts: flashback, escape, etc. Don't gate on mayPlay() — keyword-based
        // alt costs (e.g. flashback) may not register explicit mayPlay grants in all
        // engine states but are still castable via getAlternativeCosts().
        for (card in game.getCardsIn(listOf(ZoneType.Exile, ZoneType.Graveyard, ZoneType.Command))) {
            if (chooseCastAbility(card, player) != null) return true
        }
        return false
    }
}
