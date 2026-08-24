package leyline.bridge.bootstrap

import forge.deck.DeckSection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
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
    })
