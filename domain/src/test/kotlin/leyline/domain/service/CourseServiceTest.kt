package leyline.domain.service

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.domain.CollationPool
import leyline.domain.CourseDeck
import leyline.domain.CourseDeckSummary
import leyline.domain.CourseModule
import leyline.domain.DeckCard
import leyline.domain.DeckId
import leyline.domain.PlayerId
import leyline.domain.repo.InMemoryCourseRepository

class CourseServiceTest :
    FunSpec({

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
            assertSoftly {
                course.module shouldBe CourseModule.DeckSelect
                course.cardPool.size shouldBe 84
                course.cardPoolByCollation.size shouldBe 1
                course.wins shouldBe 0
                course.losses shouldBe 0
            }
        }

        test("join same event twice returns existing course") {
            val again = service.join(playerId, "Sealed_FDN_20260307")
            again.id shouldBe
                service
                    .getCoursesForPlayer(playerId)
                    .first { it.eventName == "Sealed_FDN_20260307" }
                    .id
        }

        test("setDeck transitions to CreateMatch") {
            val deck =
                CourseDeck(
                    deckId = DeckId("deck1"),
                    mainDeck = (1..40).map { DeckCard(it, 1) },
                    sideboard = (41..84).map { DeckCard(it, 1) },
                )
            val summary =
                CourseDeckSummary(
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
            assertSoftly {
                course.wins shouldBe 1
                course.losses shouldBe 0
                course.module shouldBe CourseModule.CreateMatch
            }
        }

        test("recordMatchResult updates losses") {
            val course = service.recordMatchResult(playerId, "Sealed_FDN_20260307", won = false)
            course.losses shouldBe 1
        }

        test("recordMatchResult lands on ClaimPrize at maxLosses (QuickDraft maxLosses=3)") {
            val freshPlayer = PlayerId("claim-player-A")
            service.join(freshPlayer, "QuickDraft_FDN_20260223")
            service.recordMatchResult(freshPlayer, "QuickDraft_FDN_20260223", won = false)
            service.recordMatchResult(freshPlayer, "QuickDraft_FDN_20260223", won = false)
            val third = service.recordMatchResult(freshPlayer, "QuickDraft_FDN_20260223", won = false)
            assertSoftly {
                third.losses shouldBe 3
                third.module shouldBe CourseModule.ClaimPrize
            }
        }

        test("recordMatchResult lands on ClaimPrize at maxWins (QuickDraft maxWins=7)") {
            val freshPlayer = PlayerId("claim-player-wins")
            service.join(freshPlayer, "QuickDraft_FDN_20260223")
            repeat(6) {
                service.recordMatchResult(freshPlayer, "QuickDraft_FDN_20260223", won = true)
            }
            val seventh = service.recordMatchResult(freshPlayer, "QuickDraft_FDN_20260223", won = true)
            assertSoftly {
                seventh.wins shouldBe 7
                seventh.module shouldBe CourseModule.ClaimPrize
            }
        }

        test("claimPrize flips ClaimPrize to Complete (wins/losses preserved)") {
            val freshPlayer = PlayerId("claim-player-B")
            service.join(freshPlayer, "QuickDraft_FDN_20260223")
            service.recordMatchResult(freshPlayer, "QuickDraft_FDN_20260223", won = false)
            service.recordMatchResult(freshPlayer, "QuickDraft_FDN_20260223", won = false)
            service.recordMatchResult(freshPlayer, "QuickDraft_FDN_20260223", won = false)
            val claimed = service.claimPrize(freshPlayer, "QuickDraft_FDN_20260223")
            assertSoftly {
                claimed.module shouldBe CourseModule.Complete
                claimed.losses shouldBe 3
            }
        }

        test("drop transitions to Complete") {
            val course = service.drop(playerId, "Sealed_FDN_20260307")
            course.module shouldBe CourseModule.Complete
        }

        test("join for draft event creates course with BotDraft module and empty pool") {
            val course = service.join(playerId, "QuickDraft_FDN_20260223")
            course.module shouldBe CourseModule.BotDraft
            course.cardPool shouldBe emptyList()
        }

        test("completeDraft transitions course to DeckSelect with card pool and collation ID") {
            service.join(playerId, "QuickDraft_FDN_20260223")
            val pickedCards = listOf(98353, 98519, 98350)
            val course = service.completeDraft(playerId, "QuickDraft_FDN_20260223", pickedCards)
            assertSoftly {
                course.module shouldBe CourseModule.DeckSelect
                course.cardPool shouldBe pickedCards
                course.cardPoolByCollation shouldBe listOf(CollationPool(100058, pickedCards))
            }
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
            assertSoftly {
                course.module shouldBe CourseModule.DeckSelect
                course.cardPool.size shouldBe 84
                course.wins shouldBe 0
                course.losses shouldBe 0
            }
            // old Complete course should be gone — only one course for this event
            val courses = service.getCoursesForPlayer(playerId)
            courses.count { it.eventName == "Sealed_FDN_20260307" } shouldBe 1
        }

        test("setDeck on nonexistent course throws") {
            val deck =
                CourseDeck(
                    deckId = DeckId("deck1"),
                    mainDeck = (1..40).map { DeckCard(it, 1) },
                    sideboard = (41..84).map { DeckCard(it, 1) },
                )
            val summary =
                CourseDeckSummary(
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
