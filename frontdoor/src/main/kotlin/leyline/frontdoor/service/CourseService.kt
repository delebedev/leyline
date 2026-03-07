package leyline.frontdoor.service

import leyline.frontdoor.domain.CollationPool
import leyline.frontdoor.domain.Course
import leyline.frontdoor.domain.CourseDeck
import leyline.frontdoor.domain.CourseDeckSummary
import leyline.frontdoor.domain.CourseId
import leyline.frontdoor.domain.CourseModule
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.CourseRepository
import java.util.UUID

data class GeneratedPool(
    val cards: List<Int>,
    val byCollation: List<CollationPool>,
    val collationId: Int,
)

/**
 * Manages event course lifecycle — join, deck selection, match results, drop.
 *
 * Lives in frontdoor (not matchdoor) because courses are a lobby-layer concept:
 * the client manages them via FD CmdTypes (600/603/608/622/623) before any match
 * connection exists. Pool generation is injected as a lambda to keep Forge engine
 * dependencies out of this module — the wiring layer ([leyline.infra.LeylineServer])
 * composes [leyline.game.SealedPoolGenerator] into the lambda.
 *
 * **Invisible constraint — ordering:** [join] must be called before [setDeck],
 * [enterPairing], or [recordMatchResult]. The client enforces this via its UI flow
 * (Event_Join → DeckSelect → EnterPairing), but no server-side guard exists yet.
 *
 * **Invisible constraint — match result callback:** [recordMatchResult] is called
 * from [leyline.match.MatchSession.onMatchComplete] on the Netty IO thread. The
 * repository write must be thread-safe (SQLite serialized mode handles this).
 */
class CourseService(
    private val repo: CourseRepository,
    private val generatePool: (setCode: String) -> GeneratedPool,
) {
    private fun extractSetCode(eventName: String): String {
        val parts = eventName.split("_")
        return if (parts.size >= 2 && parts[0].equals("Sealed", ignoreCase = true)) {
            parts[1]
        } else {
            "FDN"
        }
    }

    private fun isSealed(eventName: String): Boolean =
        eventName.startsWith("Sealed", ignoreCase = true)

    fun join(playerId: PlayerId, eventName: String): Course {
        repo.findByPlayerAndEvent(playerId, eventName)?.let { existing ->
            if (existing.module != CourseModule.Complete) return existing
            // Dropped/complete course — delete it so we can create a fresh one
            repo.delete(existing.id)
        }

        val course = if (isSealed(eventName)) {
            val setCode = extractSetCode(eventName)
            val pool = generatePool(setCode)
            Course(
                id = CourseId(UUID.randomUUID().toString()),
                playerId = playerId,
                eventName = eventName,
                module = CourseModule.DeckSelect,
                cardPool = pool.cards,
                cardPoolByCollation = pool.byCollation,
            )
        } else {
            Course(
                id = CourseId(UUID.randomUUID().toString()),
                playerId = playerId,
                eventName = eventName,
                module = CourseModule.CreateMatch,
            )
        }
        repo.save(course)
        return course
    }

    fun setDeck(
        playerId: PlayerId,
        eventName: String,
        deck: CourseDeck,
        summary: CourseDeckSummary,
    ): Course {
        val course = repo.findByPlayerAndEvent(playerId, eventName)
            ?: throw IllegalArgumentException("No course for $eventName")
        val updated = course.copy(
            module = CourseModule.CreateMatch,
            deck = deck,
            deckSummary = summary,
        )
        repo.save(updated)
        return updated
    }

    fun enterPairing(playerId: PlayerId, eventName: String): Course {
        val course = repo.findByPlayerAndEvent(playerId, eventName)
            ?: throw IllegalArgumentException("No course for $eventName")
        return course
    }

    fun recordMatchResult(playerId: PlayerId, eventName: String, won: Boolean): Course {
        val course = repo.findByPlayerAndEvent(playerId, eventName)
            ?: throw IllegalArgumentException("No course for $eventName")
        val updated = if (won) {
            course.copy(wins = course.wins + 1)
        } else {
            course.copy(losses = course.losses + 1)
        }
        repo.save(updated)
        return updated
    }

    fun getCoursesForPlayer(playerId: PlayerId): List<Course> =
        repo.findByPlayer(playerId)

    fun drop(playerId: PlayerId, eventName: String): Course {
        val course = repo.findByPlayerAndEvent(playerId, eventName)
            ?: throw IllegalArgumentException("No course for $eventName")
        val updated = course.copy(module = CourseModule.Complete)
        repo.save(updated)
        return updated
    }
}
