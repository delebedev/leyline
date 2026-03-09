package leyline.frontdoor.service

import leyline.frontdoor.domain.DraftSession
import leyline.frontdoor.domain.DraftSessionId
import leyline.frontdoor.domain.DraftStatus
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.DraftSessionRepository
import java.util.UUID

/**
 * Manages BotDraft session lifecycle — start, pick, status.
 *
 * Pack generation is injected as a lambda to keep Forge dependencies out.
 * Quick Draft: 3 packs x 13 picks = 39 total picks.
 */
class DraftService(
    private val repo: DraftSessionRepository,
    private val generatePacks: (setCode: String) -> List<List<Int>>,
) {
    companion object {
        const val TOTAL_PACKS = 3
    }

    fun startDraft(playerId: PlayerId, eventName: String): DraftSession {
        repo.findByPlayerAndEvent(playerId, eventName)?.let { return it }

        val packs = generatePacks(extractSetCode(eventName))
        val session = DraftSession(
            id = DraftSessionId(UUID.randomUUID().toString()),
            playerId = playerId,
            eventName = eventName,
            status = DraftStatus.PickNext,
            packNumber = 0,
            pickNumber = 0,
            draftPack = packs[0],
            packs = packs,
            pickedCards = emptyList(),
        )
        repo.save(session)
        return session
    }

    fun pick(
        playerId: PlayerId,
        eventName: String,
        cardId: Int,
        packNumber: Int,
        pickNumber: Int,
    ): DraftSession {
        val session = repo.findByPlayerAndEvent(playerId, eventName)
            ?: throw IllegalArgumentException("No draft session for $eventName")

        require(session.status == DraftStatus.PickNext) { "Draft already completed" }
        require(cardId in session.draftPack) { "Card $cardId not in current pack" }

        val newPickedCards = session.pickedCards + cardId
        val remainingPack = session.draftPack - cardId
        val totalPicksNeeded = session.packs.sumOf { it.size }
        val completed = newPickedCards.size >= totalPicksNeeded

        val (nextPackNumber, nextPickNumber, nextDraftPack) = if (completed) {
            Triple(session.packNumber, session.pickNumber, emptyList<Int>())
        } else if (remainingPack.isEmpty()) {
            val nextPN = session.packNumber + 1
            Triple(nextPN, 0, session.packs[nextPN])
        } else {
            Triple(session.packNumber, session.pickNumber + 1, remainingPack)
        }

        val updated = session.copy(
            status = if (completed) DraftStatus.Completed else DraftStatus.PickNext,
            packNumber = nextPackNumber,
            pickNumber = nextPickNumber,
            draftPack = nextDraftPack,
            pickedCards = newPickedCards,
        )
        repo.save(updated)
        return updated
    }

    fun getStatus(playerId: PlayerId, eventName: String): DraftSession? =
        repo.findByPlayerAndEvent(playerId, eventName)

    fun drop(playerId: PlayerId, eventName: String) {
        val session = repo.findByPlayerAndEvent(playerId, eventName) ?: return
        repo.delete(session.id)
    }

    private fun extractSetCode(eventName: String): String {
        val parts = eventName.split("_")
        return if (parts.size >= 2 && parts[0].equals("QuickDraft", ignoreCase = true)) {
            parts[1]
        } else {
            "FDN"
        }
    }
}
