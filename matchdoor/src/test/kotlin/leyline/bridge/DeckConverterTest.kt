package leyline.bridge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import leyline.UnitTag

class DeckConverterTest :
    FunSpec({

        tags(UnitTag)

        val db = mapOf(75515 to "Lightning Bolt", 93848 to "Counterspell")
        val nameByGrpId: (Int) -> String? = { db[it] }

        test("converts CardEntry list to deck text") {
            val main = listOf(CardEntry(75515, 4), CardEntry(93848, 2))
            val text = DeckConverter.toDeckText(main, emptyList(), nameByGrpId = nameByGrpId)
            text shouldContain "4 Lightning Bolt"
            text shouldContain "2 Counterspell"
        }

        test("includes sideboard section") {
            val main = listOf(CardEntry(75515, 4))
            val side = listOf(CardEntry(93848, 2))
            val text = DeckConverter.toDeckText(main, side, nameByGrpId = nameByGrpId)
            text shouldContain "Sideboard"
            text shouldContain "2 Counterspell"
        }

        test("empty sideboard omits header") {
            val main = listOf(CardEntry(75515, 4))
            val text = DeckConverter.toDeckText(main, emptyList(), nameByGrpId = nameByGrpId)
            text shouldNotContain "Sideboard"
        }

        test("skips unknown grpIds") {
            val main = listOf(CardEntry(99999, 1), CardEntry(75515, 4))
            val text = DeckConverter.toDeckText(main, emptyList(), nameByGrpId = nameByGrpId)
            text shouldContain "4 Lightning Bolt"
            text shouldNotContain "99999"
        }

        test("emits Commander section before main deck for Brawl") {
            val commander = listOf(CardEntry(75515, 1))
            val main = listOf(CardEntry(93848, 1))
            val text = DeckConverter.toDeckText(main, emptyList(), commander, nameByGrpId)
            text shouldContain "[Commander]"
            text shouldContain "[Deck]"
            text shouldContain "1 Lightning Bolt"
            text shouldContain "1 Counterspell"
            // [Commander] → commander card → [Deck] → main deck cards
            val commanderIdx = text.indexOf("[Commander]")
            val boltIdx = text.indexOf("1 Lightning Bolt")
            val deckIdx = text.indexOf("[Deck]")
            val counterspellIdx = text.indexOf("1 Counterspell")
            (commanderIdx < boltIdx) shouldBe true
            (boltIdx < deckIdx) shouldBe true
            (deckIdx < counterspellIdx) shouldBe true
        }

        test("empty commandZone omits Commander header") {
            val main = listOf(CardEntry(75515, 4))
            val text = DeckConverter.toDeckText(main, emptyList(), nameByGrpId = nameByGrpId)
            text shouldNotContain "[Commander]"
        }
    })
