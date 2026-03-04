package leyline.frontdoor.repo

import leyline.frontdoor.domain.Deck
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.PlayerId

interface DeckRepository {
    fun findById(id: DeckId): Deck?
    fun findAllForPlayer(playerId: PlayerId): List<Deck>
    fun save(deck: Deck)
    fun delete(id: DeckId)
}
