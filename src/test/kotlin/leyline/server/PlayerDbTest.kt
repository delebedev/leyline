package leyline.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.UnitTag
import java.io.File

class PlayerDbTest :
    FunSpec({
        tags(UnitTag)

        val testDb = File.createTempFile("player-test", ".db").also { it.deleteOnExit() }

        beforeSpec {
            PlayerDb.reset()
            PlayerDb.init(testDb)
        }

        // --- Player ---

        test("init creates tables") {
            PlayerDb.isInitialized() shouldBe true
        }

        test("upsertPlayer creates player") {
            PlayerDb.upsertPlayer("p1", "Tester")
            val p = PlayerDb.getPlayer("p1")
            p shouldNotBe null
            p!!.screenName shouldBe "Tester"
        }

        test("upsertPlayer is idempotent") {
            PlayerDb.upsertPlayer("p1", "Tester")
            PlayerDb.getPlayer("p1") shouldNotBe null
        }

        test("preferences round-trip") {
            PlayerDb.updatePreferences("p1", """{"key":"val"}""")
            PlayerDb.getPreferences("p1") shouldBe """{"key":"val"}"""
        }

        // --- Decks ---

        test("upsertDeck stores and retrieves") {
            PlayerDb.upsertDeck(
                deckId = "d1",
                playerId = "p1",
                name = "White Weenie",
                tileId = 93855,
                format = "Standard",
                cards = """{"MainDeck":[{"cardId":93855,"quantity":4}],"Sideboard":[]}""",
            )
            val d = PlayerDb.getDeck("d1")
            d shouldNotBe null
            d!!.name shouldBe "White Weenie"
            d.tileId shouldBe 93855
            d.format shouldBe "Standard"
        }

        test("getDecksForPlayer returns all") {
            PlayerDb.upsertDeck("d2", "p1", "Deck 2", 100, "Standard", "{}")
            PlayerDb.getDecksForPlayer("p1").size shouldBe 2
        }

        test("deleteDeck removes") {
            PlayerDb.deleteDeck("d2")
            PlayerDb.getDeck("d2") shouldBe null
        }

        test("upsertDeck updates existing") {
            PlayerDb.upsertDeck("d1", "p1", "Updated Name", 99, "Historic", "{}")
            val d = PlayerDb.getDeck("d1")!!
            d.name shouldBe "Updated Name"
            d.tileId shouldBe 99
            d.format shouldBe "Historic"
        }
    })
