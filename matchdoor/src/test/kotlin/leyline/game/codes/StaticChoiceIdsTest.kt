package leyline.game.codes

import forge.card.MagicColor
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class StaticChoiceIdsTest :
    FunSpec({
        tags(UnitTag)

        test("maps Forge color masks to Arena static color ids") {
            assertSoftly {
                StaticChoiceIds.colorIdForMask(MagicColor.WHITE) shouldBe 1
                StaticChoiceIds.colorIdForMask(MagicColor.BLUE) shouldBe 2
                StaticChoiceIds.colorIdForMask(MagicColor.BLACK) shouldBe 3
                StaticChoiceIds.colorIdForMask(MagicColor.RED) shouldBe 4
                StaticChoiceIds.colorIdForMask(MagicColor.GREEN) shouldBe 5
            }
        }

        test("maps normalized Forge creature type names to subtype ids") {
            assertSoftly {
                StaticChoiceIds.subtypeIdFor("Goblin") shouldBe 34
                StaticChoiceIds.subtypeIdFor("Human") shouldBe 39
                StaticChoiceIds.subtypeIdFor("Assembly-Worker") shouldBe 102
                StaticChoiceIds.subtypeIdFor("Kithkin") shouldBe 176
            }
        }
    })
