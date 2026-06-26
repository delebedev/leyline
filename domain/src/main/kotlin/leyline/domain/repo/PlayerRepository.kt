package leyline.domain.repo

import leyline.domain.Player
import leyline.domain.PlayerId
import leyline.domain.Preferences

interface PlayerRepository {
    fun findPlayer(id: PlayerId): Player?

    fun getPreferences(id: PlayerId): Preferences?

    fun savePreferences(
        id: PlayerId,
        prefs: Preferences,
    )

    fun ensurePlayer(
        id: PlayerId,
        screenName: String,
    )
}
