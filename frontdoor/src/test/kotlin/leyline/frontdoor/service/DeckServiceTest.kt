package leyline.frontdoor.service

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
import leyline.frontdoor.repo.InMemoryDeckRepository

class DeckServiceTest :
    FunSpec({
        tags(FdTag)

        val repo = InMemoryDeckRepository()
        val service = DeckService(repo)
        val playerId = PlayerId("p1")

        test("save and retrieve deck") {
            val deck = Deck(
                id = DeckId("d1"),
                playerId = playerId,
                name = "Test",
                format = Format.Standard,
                tileId = 0,
                mainDeck = listOf(DeckCard(100, 60)),
                sideboard = emptyList(),
                commandZone = emptyList(),
                companions = emptyList(),
            )
            service.save(deck)
            service.getById(DeckId("d1")) shouldNotBe null
        }

        test("listForPlayer returns player decks") {
            service.listForPlayer(playerId) shouldHaveSize 1
        }

        test("delete removes deck") {
            service.delete(DeckId("d1"))
            service.getById(DeckId("d1")) shouldBe null
        }
    })
