package leyline.frontdoor.repo

import leyline.frontdoor.domain.DraftSession
import leyline.frontdoor.domain.DraftSessionId
import leyline.frontdoor.domain.DraftStatus
import leyline.frontdoor.domain.PlayerId

class InMemoryDraftSessionRepository : DraftSessionRepository {
    private val store = mutableMapOf<DraftSessionId, DraftSession>()
    private val pods = mutableMapOf<DraftSessionId, List<List<Int>>>()

    override fun findById(id: DraftSessionId): DraftSession? = store[id]

    override fun findByPlayerAndEvent(
        playerId: PlayerId,
        eventName: String,
    ): DraftSession? = store.values.find { it.playerId == playerId && it.eventName == eventName }

    override fun save(session: DraftSession) {
        store[session.id] = session
    }

    override fun delete(id: DraftSessionId) {
        store.remove(id)
        pods.remove(id)
    }

    override fun deleteIncomplete() {
        val toDrop = store.values.filter { it.status != DraftStatus.Completed }.map { it.id }
        toDrop.forEach {
            store.remove(it)
            pods.remove(it)
        }
    }

    override fun savePodResults(
        sessionId: DraftSessionId,
        botDecks: List<List<Int>>,
    ) {
        pods[sessionId] = botDecks.map { it.toList() }
    }

    override fun findPodResults(sessionId: DraftSessionId): List<List<Int>> = pods[sessionId] ?: emptyList()
}
