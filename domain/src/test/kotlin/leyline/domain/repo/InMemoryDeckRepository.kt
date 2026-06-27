package leyline.domain.repo

import leyline.domain.Deck
import leyline.domain.DeckId
import leyline.domain.PlayerId

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
