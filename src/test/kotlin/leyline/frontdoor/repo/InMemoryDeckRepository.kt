package leyline.frontdoor.repo

import leyline.frontdoor.domain.Deck
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.PlayerId

class InMemoryDeckRepository : DeckRepository {
    private val decks = mutableMapOf<DeckId, Deck>()
    override fun findById(id: DeckId) = decks[id]
    override fun findByName(name: String) = decks.values.firstOrNull { it.name == name }
    override fun findAllForPlayer(playerId: PlayerId) = decks.values.filter { it.playerId == playerId }
    override fun save(deck: Deck) {
        decks[deck.id] = deck
    }
    override fun delete(id: DeckId) {
        decks.remove(id)
    }
}
