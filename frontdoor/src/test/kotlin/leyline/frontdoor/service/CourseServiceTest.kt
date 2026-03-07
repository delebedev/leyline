package leyline.frontdoor.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.frontdoor.FdTag
import leyline.frontdoor.domain.CollationPool
import leyline.frontdoor.domain.CourseDeck
import leyline.frontdoor.domain.CourseDeckSummary
import leyline.frontdoor.domain.CourseModule
import leyline.frontdoor.domain.DeckCard
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.InMemoryCourseRepository

class CourseServiceTest :
    FunSpec({
        tags(FdTag)

        val repo = InMemoryCourseRepository()
        val poolGen: (String) -> GeneratedPool = { _ ->
            GeneratedPool(
                cards = (1..84).toList(),
                byCollation = listOf(CollationPool(100026, (1..84).toList())),
                collationId = 100026,
            )
        }
        val service = CourseService(repo, poolGen)
        val playerId = PlayerId("p1")

        test("join sealed event creates course with DeckSelect module and card pool") {
            val course = service.join(playerId, "Sealed_FDN_20260307")
            course.module shouldBe CourseModule.DeckSelect
            course.cardPool.size shouldBe 84
            course.cardPoolByCollation.size shouldBe 1
            course.wins shouldBe 0
            course.losses shouldBe 0
        }

        test("join same event twice returns existing course") {
            val again = service.join(playerId, "Sealed_FDN_20260307")
            again.id shouldBe service.getCoursesForPlayer(playerId)
                .first { it.eventName == "Sealed_FDN_20260307" }.id
        }

        test("setDeck transitions to CreateMatch") {
            val deck = CourseDeck(
                deckId = DeckId("deck1"),
                mainDeck = (1..40).map { DeckCard(it, 1) },
                sideboard = (41..84).map { DeckCard(it, 1) },
            )
            val summary = CourseDeckSummary(
                deckId = DeckId("deck1"),
                name = "Sealed Deck",
                tileId = 12345,
                format = "Limited",
            )
            val course = service.setDeck(playerId, "Sealed_FDN_20260307", deck, summary)
            course.module shouldBe CourseModule.CreateMatch
            course.deck shouldNotBe null
        }

        test("recordMatchResult updates wins") {
            val course = service.recordMatchResult(playerId, "Sealed_FDN_20260307", won = true)
            course.wins shouldBe 1
            course.losses shouldBe 0
            course.module shouldBe CourseModule.CreateMatch
        }

        test("recordMatchResult updates losses") {
            val course = service.recordMatchResult(playerId, "Sealed_FDN_20260307", won = false)
            course.losses shouldBe 1
        }

        test("drop transitions to Complete") {
            val course = service.drop(playerId, "Sealed_FDN_20260307")
            course.module shouldBe CourseModule.Complete
        }

        test("join for draft event creates course with BotDraft module and empty pool") {
            val course = service.join(playerId, "QuickDraft_ECL_20260223")
            course.module shouldBe CourseModule.BotDraft
            course.cardPool shouldBe emptyList()
        }

        test("completeDraft transitions course to DeckSelect with card pool") {
            service.join(playerId, "QuickDraft_ECL_20260223")
            val pickedCards = listOf(98353, 98519, 98350)
            val course = service.completeDraft(playerId, "QuickDraft_ECL_20260223", pickedCards)
            course.module shouldBe CourseModule.DeckSelect
            course.cardPool shouldBe pickedCards
        }

        test("join constructed event creates course at CreateMatch with empty pool") {
            val course = service.join(playerId, "Ladder")
            course.module shouldBe CourseModule.CreateMatch
            course.cardPool shouldBe emptyList()
        }

        test("getCoursesForPlayer returns all courses") {
            val courses = service.getCoursesForPlayer(playerId)
            courses.size shouldBe 3
        }

        test("join after drop creates fresh course (re-join)") {
            val course = service.join(playerId, "Sealed_FDN_20260307")
            course.module shouldBe CourseModule.DeckSelect
            course.cardPool.size shouldBe 84
            course.wins shouldBe 0
            course.losses shouldBe 0
            // old Complete course should be gone — only one course for this event
            val courses = service.getCoursesForPlayer(playerId)
            courses.count { it.eventName == "Sealed_FDN_20260307" } shouldBe 1
        }

        test("setDeck on nonexistent course throws") {
            val deck = CourseDeck(
                deckId = DeckId("deck1"),
                mainDeck = (1..40).map { DeckCard(it, 1) },
                sideboard = (41..84).map { DeckCard(it, 1) },
            )
            val summary = CourseDeckSummary(
                deckId = DeckId("deck1"),
                name = "Sealed Deck",
                tileId = 12345,
                format = "Limited",
            )
            shouldThrow<IllegalArgumentException> {
                service.setDeck(playerId, "NonExistent_Event", deck, summary)
            }
        }

        test("enterPairing on nonexistent course throws") {
            shouldThrow<IllegalArgumentException> {
                service.enterPairing(playerId, "NonExistent_Event")
            }
        }

        test("recordMatchResult on nonexistent course throws") {
            shouldThrow<IllegalArgumentException> {
                service.recordMatchResult(playerId, "NonExistent_Event", won = true)
            }
        }

        test("drop on nonexistent course throws") {
            shouldThrow<IllegalArgumentException> {
                service.drop(playerId, "NonExistent_Event")
            }
        }
    })
