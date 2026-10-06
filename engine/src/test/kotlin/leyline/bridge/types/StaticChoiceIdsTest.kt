package leyline.bridge.types

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

        test("maps card type names independently of subtype names and generated suffixes") {
            assertSoftly {
                StaticChoiceIds.cardTypeIdFor("Artifact") shouldBe 1
                StaticChoiceIds.cardTypeIdFor("Creature") shouldBe 2
                StaticChoiceIds.cardTypeIdFor("Enchantment") shouldBe 3
                StaticChoiceIds.cardTypeIdFor("Instant") shouldBe 4
                StaticChoiceIds.cardTypeIdFor("Land") shouldBe 5
                StaticChoiceIds.cardTypeIdFor("Planeswalker") shouldBe 8
                StaticChoiceIds.cardTypeIdFor("Sorcery") shouldBe 10
                StaticChoiceIds.cardTypeIdFor("Kindred") shouldBe 11
                StaticChoiceIds.cardTypeIdFor("Battle") shouldBe 14
                StaticChoiceIds.cardTypeIdFor("Stickers") shouldBe null
                StaticChoiceIds.cardTypeIdFor("Goblin") shouldBe null
                StaticChoiceIds.subtypeIdFor("Creature") shouldBe null
            }
        }

        test("maps parity labels to the zero-based static-list ids") {
            assertSoftly {
                StaticChoiceIds.parityIdForName("Even") shouldBe 0
                StaticChoiceIds.parityIdForName("Odds") shouldBe 1
            }
        }
    })
