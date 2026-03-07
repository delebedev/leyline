package leyline.frontdoor.repo

import leyline.frontdoor.domain.DraftSession
import leyline.frontdoor.domain.DraftSessionId
import leyline.frontdoor.domain.PlayerId

interface DraftSessionRepository {
    fun findById(id: DraftSessionId): DraftSession?
    fun findByPlayerAndEvent(playerId: PlayerId, eventName: String): DraftSession?
    fun save(session: DraftSession)
    fun delete(id: DraftSessionId)
}
