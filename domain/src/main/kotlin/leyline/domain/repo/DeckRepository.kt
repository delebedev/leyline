package leyline.domain.repo

import leyline.domain.Deck
import leyline.domain.DeckId
import leyline.domain.PlayerId

interface DeckRepository {
    fun findById(id: DeckId): Deck?

    fun findByName(name: String): Deck?

    fun findAllForPlayer(playerId: PlayerId): List<Deck>

    fun save(deck: Deck)

    fun delete(id: DeckId)
}
