package leyline.native.frontdoor.service

import leyline.domain.PlayerId
import leyline.domain.Preferences
import leyline.domain.SessionId
import leyline.domain.repo.PlayerRepository
import java.util.UUID

class PlayerService(
    private val players: PlayerRepository,
) {
    fun authenticate(
        playerId: PlayerId,
        screenName: String,
    ): SessionId {
        players.ensurePlayer(playerId, screenName)
        return SessionId(UUID.randomUUID().toString())
    }

    fun getPreferences(playerId: PlayerId): Preferences? = players.getPreferences(playerId)

    fun savePreferences(
        playerId: PlayerId,
        prefs: Preferences,
    ) = players.savePreferences(playerId, prefs)
}
