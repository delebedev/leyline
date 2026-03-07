package leyline.frontdoor.repo

import leyline.frontdoor.domain.DraftSession
import leyline.frontdoor.domain.DraftSessionId
import leyline.frontdoor.domain.PlayerId

class InMemoryDraftSessionRepository : DraftSessionRepository {
    private val store = mutableMapOf<DraftSessionId, DraftSession>()

    override fun findById(id: DraftSessionId): DraftSession? = store[id]

    override fun findByPlayerAndEvent(playerId: PlayerId, eventName: String): DraftSession? =
        store.values.find { it.playerId == playerId && it.eventName == eventName }

    override fun save(session: DraftSession) {
        store[session.id] = session
    }

    override fun delete(id: DraftSessionId) {
        store.remove(id)
    }
}
