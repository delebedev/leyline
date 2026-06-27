package leyline.domain.service

import leyline.domain.CollationPool
import leyline.domain.Course
import leyline.domain.CourseDeck
import leyline.domain.CourseDeckSummary
import leyline.domain.CourseId
import leyline.domain.CourseModule
import leyline.domain.PlayerId
import leyline.domain.repo.CourseRepository
import java.util.UUID

data class GeneratedPool(
    val cards: List<Int>,
    val byCollation: List<CollationPool>,
    val collationId: Int,
)

/**
 * Manages event course lifecycle — join, deck selection, match results, drop.
 *
 * Lives in domain because courses are shared lobby-layer state:
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
 * from [MatchCoordinator.reportMatchResult] on the Netty IO thread. The
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

    fun getCourse(
        playerId: PlayerId,
        eventName: String,
    ): Course? = repo.findByPlayerAndEvent(playerId, eventName)

    fun join(
        playerId: PlayerId,
        eventName: String,
    ): Course {
        repo.findByPlayerAndEvent(playerId, eventName)?.let { existing ->
            if (existing.module != CourseModule.Complete) return existing
            // Dropped/complete course — delete it so we can create a fresh one
            repo.delete(existing.id)
        }

        val course =
            if (EventRegistry.isSealed(eventName)) {
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
            } else if (EventRegistry.isDraft(eventName)) {
                Course(
                    id = CourseId(UUID.randomUUID().toString()),
                    playerId = playerId,
                    eventName = eventName,
                    module = CourseModule.BotDraft,
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
        val course =
            repo.findByPlayerAndEvent(playerId, eventName)
                ?: throw IllegalArgumentException("No course for $eventName")
        val updated =
            course.copy(
                module = CourseModule.CreateMatch,
                deck = deck,
                deckSummary = summary,
            )
        repo.save(updated)
        return updated
    }

    fun enterPairing(
        playerId: PlayerId,
        eventName: String,
    ): Course {
        val course =
            repo.findByPlayerAndEvent(playerId, eventName)
                ?: throw IllegalArgumentException("No course for $eventName")
        return course
    }

    fun recordMatchResult(
        playerId: PlayerId,
        eventName: String,
        won: Boolean,
    ): Course {
        val course =
            repo.findByPlayerAndEvent(playerId, eventName)
                ?: throw IllegalArgumentException("No course for $eventName")
        val event = EventRegistry.findEvent(eventName)
        val updated =
            if (won) {
                course.copy(wins = course.wins + 1)
            } else {
                course.copy(losses = course.losses + 1)
            }
        val gateReached =
            (event?.maxWins != null && updated.wins >= event.maxWins) ||
                (event?.maxLosses != null && updated.losses >= event.maxLosses)
        // Reach max wins or losses → ClaimPrize. Client polls EventGetCoursesV2,
        // sees the new module, and shows the "Claim Prize" button. Player clicks
        // it → Event_ClaimPrize (607) → [claimPrize] flips ClaimPrize → Complete.
        val result =
            if (gateReached) updated.copy(module = CourseModule.ClaimPrize) else updated
        repo.save(result)
        return result
    }

    /**
     * Player clicked "Claim Prize" — finalize the course. Returns the saved course
     * (module flipped to [CourseModule.Complete]) so the wire builder can echo it.
     */
    fun claimPrize(
        playerId: PlayerId,
        eventName: String,
    ): Course {
        val course =
            repo.findByPlayerAndEvent(playerId, eventName)
                ?: throw IllegalArgumentException("No course for $eventName")
        val updated = course.copy(module = CourseModule.Complete)
        repo.save(updated)
        return updated
    }

    fun completeDraft(
        playerId: PlayerId,
        eventName: String,
        pickedCards: List<Int>,
        collationId: Int = 0,
    ): Course {
        val course =
            repo.findByPlayerAndEvent(playerId, eventName)
                ?: throw IllegalArgumentException("No course for $eventName")
        val updated =
            course.copy(
                module = CourseModule.DeckSelect,
                cardPool = pickedCards,
                cardPoolByCollation = listOf(CollationPool(collationId, pickedCards)),
            )
        repo.save(updated)
        return updated
    }

    fun getCoursesForPlayer(playerId: PlayerId): List<Course> = repo.findByPlayer(playerId)

    fun drop(
        playerId: PlayerId,
        eventName: String,
    ): Course {
        val course =
            repo.findByPlayerAndEvent(playerId, eventName)
                ?: throw IllegalArgumentException("No course for $eventName")
        val updated = course.copy(module = CourseModule.Complete)
        repo.save(updated)
        return updated
    }
}
