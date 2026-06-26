package leyline.infra.persistence

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.domain.CollationPool
import leyline.domain.Course
import leyline.domain.CourseDeck
import leyline.domain.CourseDeckSummary
import leyline.domain.CourseId
import leyline.domain.CourseModule
import leyline.domain.DeckCard
import leyline.domain.DeckId
import leyline.domain.PlayerId
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File

class SqliteCourseRepositoryTest :
    FunSpec({

        val dbFile = File.createTempFile("test-courses", ".db")
        val db = Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")
        val store = SqlitePlayerStore(db)

        beforeSpec { store.createTables() }
        afterSpec { dbFile.delete() }

        val playerId = PlayerId("p1")
        val courseId = CourseId("course-1")

        test("save and retrieve course") {
            val course =
                Course(
                    id = courseId,
                    playerId = playerId,
                    eventName = "Sealed_FDN_20260307",
                    module = CourseModule.DeckSelect,
                    cardPool = listOf(1, 2, 3),
                    cardPoolByCollation = listOf(CollationPool(100026, listOf(1, 2, 3))),
                )
            store.save(course)
            val found = store.findById(courseId)
            assertSoftly {
                found shouldNotBe null
                found!!.eventName shouldBe "Sealed_FDN_20260307"
                found.module shouldBe CourseModule.DeckSelect
                found.cardPool shouldBe listOf(1, 2, 3)
                found.cardPoolByCollation shouldHaveSize 1
            }
        }

        test("findByPlayerAndEvent") {
            val found = store.findByPlayerAndEvent(playerId, "Sealed_FDN_20260307")
            found shouldNotBe null
            found!!.id shouldBe courseId
        }

        test("findByPlayer returns all player courses") {
            val course2 =
                Course(
                    id = CourseId("course-2"),
                    playerId = playerId,
                    eventName = "Ladder",
                    module = CourseModule.CreateMatch,
                )
            store.save(course2)
            store.findByPlayer(playerId) shouldHaveSize 2
        }

        test("save updates existing course") {
            val updated =
                Course(
                    id = courseId,
                    playerId = playerId,
                    eventName = "Sealed_FDN_20260307",
                    module = CourseModule.CreateMatch,
                    wins = 2,
                    losses = 1,
                    cardPool = listOf(1, 2, 3),
                    cardPoolByCollation = listOf(CollationPool(100026, listOf(1, 2, 3))),
                    deck =
                        CourseDeck(
                            deckId = DeckId("d1"),
                            mainDeck = listOf(DeckCard(1, 1)),
                            sideboard = listOf(DeckCard(2, 1)),
                        ),
                    deckSummary =
                        CourseDeckSummary(
                            deckId = DeckId("d1"),
                            name = "My Sealed",
                            tileId = 12345,
                            format = "Limited",
                        ),
                )
            store.save(updated)
            val found = store.findById(courseId)!!
            assertSoftly {
                found.wins shouldBe 2
                found.losses shouldBe 1
                found.module shouldBe CourseModule.CreateMatch
                found.deck shouldNotBe null
                found.deckSummary shouldNotBe null
            }
        }

        test("delete removes course") {
            store.delete(courseId)
            store.findById(courseId) shouldBe null
        }
    })
