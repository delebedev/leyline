package leyline.domain.service

import leyline.domain.DraftSession
import leyline.domain.DraftSessionId
import leyline.domain.DraftStatus
import leyline.domain.PlayerId
import leyline.domain.repo.DraftSessionRepository
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Manages BotDraft session lifecycle — start, pick, status.
 *
 * Pack-and-pass logic is delegated to a [Driver] (Forge `BoosterDraft` in production)
 * to keep Forge dependencies out of frontdoor. Each pick advances the human seat;
 * the driver internally drives the 7 bot seats.
 */
class DraftService(
    private val repo: DraftSessionRepository,
    private val driver: Driver,
) {
    private val log = LoggerFactory.getLogger(DraftService::class.java)

    /**
     * Pack-and-pass driver. Lifetime is per-session: [start] then a series of
     * [pick] calls until [PickOutcome.complete], then [complete] for the pod.
     *
     * `sessionKey` is the [DraftSessionId.value] — the driver does not need to know
     * about the rest of the session model.
     */
    interface Driver {
        fun start(
            sessionKey: String,
            setCode: String,
        ): List<Int>

        fun pick(
            sessionKey: String,
            grpId: Int,
        ): PickOutcome

        fun complete(sessionKey: String): PodOutcome
    }

    data class PickOutcome(
        val packNumber: Int,
        val pickNumber: Int,
        val nextPack: List<Int>,
        val complete: Boolean,
    )

    data class PodOutcome(
        val playerPool: List<Int>,
        val botDecks: List<List<Int>>,
    )

    fun startDraft(
        playerId: PlayerId,
        eventName: String,
    ): DraftSession {
        repo.findByPlayerAndEvent(playerId, eventName)?.let { return it }

        val sessionId = DraftSessionId(UUID.randomUUID().toString())
        val firstPack = driver.start(sessionId.value, extractSetCode(eventName))
        val session =
            DraftSession(
                id = sessionId,
                playerId = playerId,
                eventName = eventName,
                status = DraftStatus.PickNext,
                packNumber = 0,
                pickNumber = 0,
                draftPack = firstPack,
                pickedCards = emptyList(),
            )
        repo.save(session)
        return session
    }

    fun pick(
        playerId: PlayerId,
        eventName: String,
        cardId: Int,
        @Suppress("UnusedParameter") packNumber: Int,
        @Suppress("UnusedParameter") pickNumber: Int,
    ): DraftSession {
        val session =
            repo.findByPlayerAndEvent(playerId, eventName)
                ?: throw IllegalArgumentException("No draft session for $eventName")

        require(session.status == DraftStatus.PickNext) { "Draft already completed" }
        require(cardId in session.draftPack) { "Card $cardId not in current pack" }

        val outcome = driver.pick(session.id.value, cardId)
        val updated =
            session.copy(
                status = if (outcome.complete) DraftStatus.Completed else DraftStatus.PickNext,
                packNumber = outcome.packNumber,
                pickNumber = outcome.pickNumber,
                draftPack = outcome.nextPack,
                pickedCards = session.pickedCards + cardId,
            )
        repo.save(updated)

        if (outcome.complete) {
            val pod = driver.complete(session.id.value)
            repo.savePodResults(session.id, pod.botDecks)
            log.info(
                "Draft complete: session={} player={} bots={} avg-bot-deck={}",
                session.id.value,
                playerId.value,
                pod.botDecks.size,
                pod.botDecks.map { it.size }.average(),
            )
        }
        return updated
    }

    fun getStatus(
        playerId: PlayerId,
        eventName: String,
    ): DraftSession? = repo.findByPlayerAndEvent(playerId, eventName)

    fun drop(
        playerId: PlayerId,
        eventName: String,
    ) {
        val session = repo.findByPlayerAndEvent(playerId, eventName) ?: return
        repo.delete(session.id)
    }

    /**
     * Boot-time cleanup. The current [Driver] keeps per-session state only in
     * memory, so any [DraftStatus.PickNext] row in the DB at boot has lost its
     * driver counterpart and is unrecoverable. Drop those rows so the player
     * isn't offered a "Resume" tile that would error on the first pick.
     *
     * If a future driver persists checkpoints, this method's contract changes:
     * either drop only sessions whose driver state actually went missing (and
     * keep the rest), or move the call site behind a flag.
     */
    fun discardIncompleteSessions() {
        repo.deleteIncomplete()
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
