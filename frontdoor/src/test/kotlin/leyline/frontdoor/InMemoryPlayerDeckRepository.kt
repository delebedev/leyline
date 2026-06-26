package leyline.frontdoor

import leyline.domain.Deck
import leyline.domain.DeckId
import leyline.domain.Player
import leyline.domain.PlayerId
import leyline.domain.Preferences
import leyline.domain.repo.DeckRepository
import leyline.domain.repo.PlayerRepository

class InMemoryPlayerDeckRepository : DeckRepository, PlayerRepository {
    private val decks = mutableMapOf<DeckId, Deck>()
    private val players = mutableMapOf<PlayerId, Player>()
    private val prefs = mutableMapOf<PlayerId, Preferences>()

    override fun findById(id: DeckId) = decks[id]

    override fun findByName(name: String) = decks.values.firstOrNull { it.name == name }
    override fun findAllForPlayer(playerId: PlayerId) = decks.values.filter { it.playerId == playerId }

    override fun save(deck: Deck) {
        decks[deck.id] = deck
    }

    override fun delete(id: DeckId) {
        decks.remove(id)
    }

    override fun findPlayer(id: PlayerId) = players[id]
    override fun getPreferences(id: PlayerId) = prefs[id]

    override fun savePreferences(
        id: PlayerId,
        prefs: Preferences,
    ) {
        this.prefs[id] = prefs
    }

    override fun ensurePlayer(
        id: PlayerId,
        screenName: String,
    ) {
        players.putIfAbsent(id, Player(id, screenName))
    }
}
