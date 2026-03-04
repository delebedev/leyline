package leyline.frontdoor.service

import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.DeckRepository
import java.util.UUID

data class MatchInfo(val matchId: String, val host: String, val port: Int)

class MatchmakingService(
    private val decks: DeckRepository,
    private val matchDoorHost: String,
    private val matchDoorPort: Int,
) {
    fun startAiMatch(playerId: PlayerId, deckId: DeckId): MatchInfo {
        decks.findById(deckId) // validate exists (future: validate legality)
        return MatchInfo(
            matchId = UUID.randomUUID().toString(),
            host = matchDoorHost,
            port = matchDoorPort,
        )
    }
}
