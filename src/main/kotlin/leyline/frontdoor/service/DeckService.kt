package leyline.frontdoor.service

import leyline.frontdoor.domain.Deck
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.DeckRepository

class DeckService(private val decks: DeckRepository) {
    fun listForPlayer(playerId: PlayerId): List<Deck> = decks.findAllForPlayer(playerId)
    fun getById(id: DeckId): Deck? = decks.findById(id)
    fun save(deck: Deck) = decks.save(deck)
    fun delete(id: DeckId) = decks.delete(id)
}
