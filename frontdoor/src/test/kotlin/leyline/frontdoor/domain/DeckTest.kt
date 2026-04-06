package leyline.frontdoor.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.frontdoor.FdTag

class DeckTest :
    FunSpec({
        tags(FdTag)

        test("totalCards sums main deck quantities") {
            val deck = Deck(
                id = DeckId("d1"), playerId = PlayerId("p1"), name = "Test",
                format = Format.Standard, tileId = 0,
                mainDeck = listOf(DeckCard(100, 4), DeckCard(200, 56)),
                sideboard = listOf(DeckCard(300, 15)),
                commandZone = emptyList(), companions = emptyList(),
            )
            deck.totalCards shouldBe 60
        }

        test("Format.fromString is case-insensitive") {
            Format.fromString("standard") shouldBe Format.Standard
            Format.fromString("HISTORIC") shouldBe Format.Historic
            Format.fromString("unknown") shouldBe Format.Standard
        }
    })
