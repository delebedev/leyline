package leyline.bridge.bootstrap

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.domain.DeckCard
import leyline.domain.deck.DeckCards
import leyline.domain.deck.DeckSource
import forge.deck.DeckSection as ForgeDeckSection

class DeckLoaderTest :
    FunSpec({
        tags(UnitTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        val names = mapOf(1 to "Lightning Bolt", 2 to "Negate", 3 to "Atraxa, Praetors' Voice", 4 to "Lurrus of the Dream-Den")
        val nameByGrpId: (Int) -> String? = { names[it] }

        test("Cards source places main, sideboard, and commander sections") {
            val cards =
                DeckCards(
                    mainDeck = listOf(DeckCard(1, 4)),
                    sideboard = listOf(DeckCard(2, 2)),
                    commandZone = listOf(DeckCard(3, 1)),
                )

            val deck = DeckLoader.load(DeckSource.Cards(cards), nameByGrpId)

            assertSoftly {
                deck.getOrCreate(ForgeDeckSection.Main).toFlatList() shouldHaveSize 4
                deck.getOrCreate(ForgeDeckSection.Sideboard).toFlatList() shouldHaveSize 2
                deck.getOrCreate(ForgeDeckSection.Commander).toFlatList() shouldHaveSize 1
            }
        }

        test("Cards source maps companions into the Forge sideboard") {
            val cards = DeckCards(mainDeck = listOf(DeckCard(1, 4)), companions = listOf(DeckCard(4, 1)))

            val deck = DeckLoader.load(DeckSource.Cards(cards), nameByGrpId)

            deck
                .getOrCreate(ForgeDeckSection.Sideboard)
                .toFlatList()
                .single()
                .name shouldBe "Lurrus of the Dream-Den"
        }

        test("Cards source rejects the whole deck on any unknown grpId") {
            val cards = DeckCards(mainDeck = listOf(DeckCard(1, 4), DeckCard(999, 1)), sideboard = listOf(DeckCard(998, 1)))

            val ex = shouldThrow<DeckRealizationException> { DeckLoader.load(DeckSource.Cards(cards), nameByGrpId) }

            ex.errors shouldHaveSize 2
        }

        test("ForgeText source builds a deck from plain decklist text") {
            val deck = DeckLoader.load(DeckSource.ForgeText("4 Lightning Bolt\nSideboard\n2 Negate"))

            deck.getOrCreate(ForgeDeckSection.Main).toFlatList() shouldHaveSize 4
            deck.getOrCreate(ForgeDeckSection.Sideboard).toFlatList() shouldHaveSize 2
        }

        test("ForgeText source rejects the whole deck on an unrecognized card name") {
            val ex =
                shouldThrow<DeckRealizationException> {
                    DeckLoader.load(DeckSource.ForgeText("4 Lightning Bolt\n2 Not A Real Card"))
                }

            ex.errors shouldHaveSize 1
        }

        test("ForgeText source rejects the whole deck on unstructured garbage text") {
            val ex =
                shouldThrow<DeckRealizationException> {
                    DeckLoader.load(DeckSource.ForgeText("4 Lightning Bolt\nthis is not a deck line at all"))
                }

            ex.errors shouldHaveSize 1
        }
    })
