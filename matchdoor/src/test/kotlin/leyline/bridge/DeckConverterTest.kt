package leyline.bridge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import leyline.UnitTag
import leyline.frontdoor.domain.DeckCard

class DeckConverterTest :
    FunSpec({

        tags(UnitTag)

        val db = mapOf(75515 to "Lightning Bolt", 93848 to "Counterspell")
        val nameByGrpId: (Int) -> String? = { db[it] }

        test("converts DeckCard list to deck text") {
            val main = listOf(DeckCard(75515, 4), DeckCard(93848, 2))
            val text = DeckConverter.toDeckText(main, emptyList(), nameByGrpId)
            text shouldContain "4 Lightning Bolt"
            text shouldContain "2 Counterspell"
        }

        test("includes sideboard section") {
            val main = listOf(DeckCard(75515, 4))
            val side = listOf(DeckCard(93848, 2))
            val text = DeckConverter.toDeckText(main, side, nameByGrpId)
            text shouldContain "Sideboard"
            text shouldContain "2 Counterspell"
        }

        test("empty sideboard omits header") {
            val main = listOf(DeckCard(75515, 4))
            val text = DeckConverter.toDeckText(main, emptyList(), nameByGrpId)
            text shouldNotContain "Sideboard"
        }

        test("skips unknown grpIds") {
            val main = listOf(DeckCard(99999, 1), DeckCard(75515, 4))
            val text = DeckConverter.toDeckText(main, emptyList(), nameByGrpId)
            text shouldContain "4 Lightning Bolt"
            text shouldNotContain "99999"
        }
    })
