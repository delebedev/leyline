package leyline.frontdoor.service

import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.MatchInfo
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.DeckRepository
import java.util.UUID

class MatchmakingService(
    private val decks: DeckRepository,
    private val matchDoorHost: String,
    private val matchDoorPort: Int,
) {
    fun startAiMatch(playerId: PlayerId, deckId: DeckId, eventName: String = "AIBotMatch"): MatchInfo {
        decks.findById(deckId) // validate exists (future: validate legality)
        return MatchInfo(
            matchId = UUID.randomUUID().toString(),
            host = matchDoorHost,
            port = matchDoorPort,
            eventName = eventName,
        )
    }
}
