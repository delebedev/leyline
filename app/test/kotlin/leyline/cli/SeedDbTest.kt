package leyline.cli

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import leyline.UnitTag
import leyline.domain.Deck
import leyline.domain.DeckCard
import leyline.domain.DeckId
import leyline.domain.Format
import leyline.domain.PlayerId
import leyline.domain.SystemPlayers
import leyline.domain.deck.parseDecklist
import leyline.domain.repo.DeckRepository
import java.io.File

class SeedDbTest :
    FunSpec({
        tags(UnitTag)

        test("reconciles retired spectator decks, including an empty rotation") {
            val decks = TestDeckRepository()
            val active = spectatorDeck("active")
            val retired = spectatorDeck("retired")
            decks.save(active)
            decks.save(retired)

            reconcileSpectatorDecks(decks, setOf(active.id))
            decks.findAllForPlayer(SystemPlayers.SPECTATOR).map { it.id } shouldContainExactly listOf(active.id)

            reconcileSpectatorDecks(decks, emptySet())
            decks.findAllForPlayer(SystemPlayers.SPECTATOR) shouldContainExactly emptyList()
        }

        test("parses every bundled data/decks/*.txt file") {
            val decksDir = File("data/decks")
            val deckFiles = decksDir.listFiles { f -> f.extension == "txt" }.orEmpty().toList()
            deckFiles.size shouldBeGreaterThan 0

            for (file in deckFiles) {
                withClue(file.name) {
                    parseDecklist(file.readText()).entries.size shouldBeGreaterThan 0
                }
            }
        }
    })

private fun spectatorDeck(id: String) =
    Deck(
        id = DeckId(id),
        playerId = SystemPlayers.SPECTATOR,
        name = id,
        format = Format.Standard,
        tileId = 1,
        mainDeck = listOf(DeckCard(1, 60)),
        sideboard = emptyList(),
        commandZone = emptyList(),
        companions = emptyList(),
    )

private class TestDeckRepository : DeckRepository {
    private val decks = mutableMapOf<DeckId, Deck>()

    override fun findById(id: DeckId): Deck? = decks[id]

    override fun findByName(name: String): Deck? = decks.values.firstOrNull { it.name == name }

    override fun findAllForPlayer(playerId: PlayerId): List<Deck> = decks.values.filter { it.playerId == playerId }

    override fun save(deck: Deck) {
        decks[deck.id] = deck
    }

    override fun delete(id: DeckId) {
        decks.remove(id)
    }
}
