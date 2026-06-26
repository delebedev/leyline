package leyline.bridge.types

import forge.card.MagicColor
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

class WubrgColorMappingTest :
    FunSpec({
        tags(UnitTag)

        test("maps mana colors to Forge magic color masks") {
            assertSoftly {
                WubrgColorMapping.magicMaskForManaColor(ManaColor.White_afc9) shouldBe MagicColor.WHITE
                WubrgColorMapping.magicMaskForManaColor(ManaColor.Green_afc9) shouldBe MagicColor.GREEN
                WubrgColorMapping.magicMaskForManaColor(ManaColor.Generic).shouldBeNull()
            }
        }

        test("maps Forge masks to static color ids") {
            assertSoftly {
                WubrgColorMapping.staticIdForMagicMask(MagicColor.WHITE) shouldBe 1
                WubrgColorMapping.staticIdForMagicMask(MagicColor.BLUE) shouldBe 2
                WubrgColorMapping.staticIdForMagicMask(0.toByte()).shouldBeNull()
            }
        }

        test("maps color names and one-letter aliases to static color ids") {
            assertSoftly {
                WubrgColorMapping.staticIdForName("white") shouldBe 1
                WubrgColorMapping.staticIdForName("U") shouldBe 2
                WubrgColorMapping.staticIdForName("purple").shouldBeNull()
            }
        }

        test("expands a combined MagicColor mask to mana color numbers") {
            WubrgColorMapping.manaColorNumbersFromMagicMask(
                MagicColor.WHITE.toInt() or MagicColor.BLACK.toInt() or MagicColor.GREEN.toInt(),
            ) shouldBe listOf(ManaColor.White_afc9.number, ManaColor.Black_afc9.number, ManaColor.Green_afc9.number)
        }
    })
