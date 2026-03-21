package leyline.frontdoor.repo

import leyline.frontdoor.domain.Player
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.domain.Preferences

class InMemoryPlayerRepository : PlayerRepository {
    private val players = mutableMapOf<PlayerId, Player>()
    private val prefs = mutableMapOf<PlayerId, Preferences>()
    override fun findPlayer(id: PlayerId) = players[id]
    override fun firstPlayer() = players.values.firstOrNull()
    override fun getPreferences(id: PlayerId) = prefs[id]
    override fun savePreferences(id: PlayerId, prefs: Preferences) {
        this.prefs[id] = prefs
    }
    override fun ensurePlayer(id: PlayerId, screenName: String) {
        players.putIfAbsent(id, Player(id, screenName))
    }
}
