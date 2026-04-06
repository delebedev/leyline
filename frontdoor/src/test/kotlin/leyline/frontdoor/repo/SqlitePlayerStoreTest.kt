package leyline.frontdoor.repo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.frontdoor.FdTag
import leyline.frontdoor.domain.Deck
import leyline.frontdoor.domain.DeckCard
import leyline.frontdoor.domain.DeckId
import leyline.frontdoor.domain.Format
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.domain.Preferences
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File

class SqlitePlayerStoreTest :
    FunSpec({
        tags(FdTag)

        val dbFile = File.createTempFile("sqlite-store-test", ".db").also { it.deleteOnExit() }
        val db = Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")
        val store = SqlitePlayerStore(db)

        beforeSpec { store.createTables() }

        val pid = PlayerId("player-1")

        // --- PlayerRepository ---

        test("ensurePlayer creates player") {
            store.ensurePlayer(pid, "Tester")
            val p = store.findPlayer(pid)
            p shouldNotBe null
            p!!.screenName shouldBe "Tester"
        }

        test("ensurePlayer is idempotent") {
            store.ensurePlayer(pid, "Different Name")
            store.findPlayer(pid)!!.screenName shouldBe "Tester"
        }

        test("preferences round-trip") {
            val prefs = Preferences("""{"sounds":false}""")
            store.savePreferences(pid, prefs)
            store.getPreferences(pid) shouldBe prefs
        }

        // --- DeckRepository ---

        test("save and find deck") {
            val deck = Deck(
                id = DeckId("deck-1"),
                playerId = pid,
                name = "White Weenie",
                format = Format.Standard,
                tileId = 93855,
                mainDeck = listOf(DeckCard(93855, 4), DeckCard(93800, 3)),
                sideboard = listOf(DeckCard(10000, 2)),
                commandZone = emptyList(),
                companions = emptyList(),
            )
            store.save(deck)

            val found = store.findById(DeckId("deck-1"))
            found shouldNotBe null
            found!!.name shouldBe "White Weenie"
            found.tileId shouldBe 93855
            found.format shouldBe Format.Standard
            found.mainDeck shouldHaveSize 2
            found.sideboard shouldHaveSize 1
        }

        test("findAllForPlayer returns all decks") {
            store.save(
                Deck(
                    id = DeckId("deck-2"),
                    playerId = pid,
                    name = "Mono Red",
                    format = Format.Standard,
                    tileId = 100,
                    mainDeck = emptyList(),
                    sideboard = emptyList(),
                    commandZone = emptyList(),
                    companions = emptyList(),
                ),
            )
            store.findAllForPlayer(pid) shouldHaveSize 2
        }

        test("delete removes deck") {
            store.delete(DeckId("deck-2"))
            store.findById(DeckId("deck-2")) shouldBe null
        }

        test("save updates existing deck") {
            val updated = Deck(
                id = DeckId("deck-1"),
                playerId = pid,
                name = "Updated Weenie",
                format = Format.Historic,
                tileId = 99,
                mainDeck = listOf(DeckCard(93855, 4)),
                sideboard = emptyList(),
                commandZone = emptyList(),
                companions = emptyList(),
            )
            store.save(updated)

            val found = store.findById(DeckId("deck-1"))!!
            found.name shouldBe "Updated Weenie"
            found.tileId shouldBe 99
            found.format shouldBe Format.Historic
            found.mainDeck shouldHaveSize 1
        }
    })
