package leyline.domain.service

import leyline.domain.Deck
import leyline.domain.DeckId
import leyline.domain.PlayerId
import leyline.domain.repo.DeckRepository

class DeckService(
    private val decks: DeckRepository,
) {
    fun listForPlayer(playerId: PlayerId): List<Deck> = decks.findAllForPlayer(playerId)

    fun getById(id: DeckId): Deck? = decks.findById(id)

    fun getByName(name: String): Deck? = decks.findByName(name)

    fun save(deck: Deck) = decks.save(deck)

    fun delete(id: DeckId) = decks.delete(id)
}
