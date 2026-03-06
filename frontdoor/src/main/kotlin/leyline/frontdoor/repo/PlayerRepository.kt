package leyline.frontdoor.repo

import leyline.frontdoor.domain.Player
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.domain.Preferences

interface PlayerRepository {
    fun findPlayer(id: PlayerId): Player?
    fun getPreferences(id: PlayerId): Preferences?
    fun savePreferences(id: PlayerId, prefs: Preferences)
    fun ensurePlayer(id: PlayerId, screenName: String)
}
