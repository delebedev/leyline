package leyline.bridge.bootstrap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class DeckConverterTest :
    FunSpec({

        tags(UnitTag)

        val db = mapOf(75515 to "Lightning Bolt", 93848 to "Counterspell")
        val nameByGrpId: (Int) -> String? = { db[it] }

        test("converts CardEntry list to deck text") {
            val main = listOf(CardEntry(75515, 4), CardEntry(93848, 2))
            val text = DeckConverter.toDeckText(main, emptyList(), nameByGrpId = nameByGrpId)

            text shouldBe "4 Lightning Bolt\n2 Counterspell\n"
        }

        test("includes sideboard section") {
            val main = listOf(CardEntry(75515, 4))
            val side = listOf(CardEntry(93848, 2))
            val text = DeckConverter.toDeckText(main, side, nameByGrpId = nameByGrpId)

            text shouldBe "4 Lightning Bolt\nSideboard\n2 Counterspell\n"
        }

        test("empty sideboard omits header") {
            val main = listOf(CardEntry(75515, 4))
            val text = DeckConverter.toDeckText(main, emptyList(), nameByGrpId = nameByGrpId)

            text shouldBe "4 Lightning Bolt\n"
        }

        test("skips unknown grpIds") {
            val main = listOf(CardEntry(99999, 1), CardEntry(75515, 4))
            val text = DeckConverter.toDeckText(main, emptyList(), nameByGrpId = nameByGrpId)

            text shouldBe "4 Lightning Bolt\n"
        }

        test("emits Commander section before main deck for Brawl") {
            val commander = listOf(CardEntry(75515, 1))
            val main = listOf(CardEntry(93848, 1))
            val text = DeckConverter.toDeckText(main, emptyList(), commander, nameByGrpId)

            text shouldBe "[Commander]\n1 Lightning Bolt\n[Deck]\n1 Counterspell\n"
        }

        test("empty commandZone omits Commander header") {
            val main = listOf(CardEntry(75515, 4))
            val text = DeckConverter.toDeckText(main, emptyList(), nameByGrpId = nameByGrpId)

            text shouldBe "4 Lightning Bolt\n"
        }
    })
