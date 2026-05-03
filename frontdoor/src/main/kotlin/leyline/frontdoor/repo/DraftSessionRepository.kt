package leyline.frontdoor.repo

import leyline.frontdoor.domain.DraftSession
import leyline.frontdoor.domain.DraftSessionId
import leyline.frontdoor.domain.PlayerId

interface DraftSessionRepository {
    fun findById(id: DraftSessionId): DraftSession?

    fun findByPlayerAndEvent(
        playerId: PlayerId,
        eventName: String,
    ): DraftSession?

    fun save(session: DraftSession)

    fun delete(id: DraftSessionId)

    /**
     * Drop every session whose status is not [leyline.frontdoor.domain.DraftStatus.Completed].
     * Called on server boot to clear in-flight drafts whose driver state lives only
     * in memory.
     */
    fun deleteIncomplete()

    /**
     * Persist the 7 bot decks Forge built for a pod at draft completion.
     * Each entry is a flat list of grpIds (with duplicates per quantity).
     * `seatIndex` is the index in the list (0..6, matches Forge's seat 1..7).
     */
    fun savePodResults(
        sessionId: DraftSessionId,
        botDecks: List<List<Int>>,
    )

    /** 7 bot decks for [sessionId], or empty if none persisted. Index 0 = seat 1. */
    fun findPodResults(sessionId: DraftSessionId): List<List<Int>>
}
