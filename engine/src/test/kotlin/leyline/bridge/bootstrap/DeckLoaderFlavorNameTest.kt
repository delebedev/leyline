package leyline.bridge.bootstrap

import forge.deck.DeckSection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.domain.DeckCard
import leyline.domain.deck.DeckCards
import leyline.domain.deck.DeckSource

class DeckLoaderFlavorNameTest :
    FunSpec({
        tags(UnitTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        test("loads normalized Universes Within flavor name") {
            val deck = DeckLoader.load(DeckSource.ForgeText("1 Detect Intrusion"))

            deck
                .getOrCreate(DeckSection.Main)
                .toFlatList()
                .single()
                .name shouldBe "Spider-Sense"
        }

        test("loads normalized flavor name from resolved cards") {
            val source = DeckSource.Cards(DeckCards(mainDeck = listOf(DeckCard(1, 1))))
            val deck = DeckLoader.load(source) { "Detect Intrusion" }

            deck
                .getOrCreate(DeckSection.Main)
                .toFlatList()
                .single()
                .name shouldBe "Spider-Sense"
        }
    })
