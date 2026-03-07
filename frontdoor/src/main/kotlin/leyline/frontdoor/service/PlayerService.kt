package leyline.frontdoor.service

import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.domain.Preferences
import leyline.frontdoor.domain.SessionId
import leyline.frontdoor.repo.PlayerRepository
import java.util.UUID

class PlayerService(private val players: PlayerRepository) {
    fun authenticate(playerId: PlayerId, screenName: String): SessionId {
        players.ensurePlayer(playerId, screenName)
        return SessionId(UUID.randomUUID().toString())
    }

    fun getPreferences(playerId: PlayerId): Preferences? =
        players.getPreferences(playerId)

    fun savePreferences(playerId: PlayerId, prefs: Preferences) =
        players.savePreferences(playerId, prefs)
}
