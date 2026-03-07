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
        repo.findByPlayerAndEvent(playerId, eventName)?.let { return it }

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
