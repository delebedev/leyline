package leyline.game.data

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class EvergreenKeywordsTest :
    FunSpec({

        tags(UnitTag)

        fun card(vararg abilityIds: Int) =
            CardData(
                grpId = 1,
                titleId = 1,
                power = "",
                toughness = "",
                colors = emptyList(),
                types = emptyList(),
                subtypes = emptyList(),
                supertypes = emptyList(),
                abilityIds = abilityIds.map { it to 0 },
                manaCost = emptyList(),
            )

        test("reads keywords off the ability ids a card carries") {
            assertSoftly {
                // Serra Angel: flying and vigilance.
                EvergreenKeywords.of(card(8, 15)) shouldBe listOf("Flying", "Vigilance")
                EvergreenKeywords.of(card(6)) shouldBe listOf("First strike")
                EvergreenKeywords.of(card(2)) shouldBe listOf("Defender")
            }
        }

        test("orders keywords by the table, not by the card's ability order") {
            EvergreenKeywords.of(card(15, 9, 1)) shouldBe listOf("Deathtouch", "Haste", "Vigilance")
        }

        test("ignores ability ids that are not evergreen keywords") {
            assertSoftly {
                EvergreenKeywords.of(card(86788, 823203)).shouldBeEmpty()
                EvergreenKeywords.of(card()).shouldBeEmpty()
                // A keyword alongside a card-specific ability keeps only the keyword.
                EvergreenKeywords.of(card(4173, 6)) shouldBe listOf("First strike")
            }
        }
    })
