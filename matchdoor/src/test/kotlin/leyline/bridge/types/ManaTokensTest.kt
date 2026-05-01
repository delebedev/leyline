package leyline.bridge.types

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

class ManaTokensTest :
    FunSpec({
        tags(UnitTag)

        test("color letters map to ManaColor") {
            manaTokenToPair("W") shouldBe (ManaColor.White_afc9 to 1)
            manaTokenToPair("U") shouldBe (ManaColor.Blue_afc9 to 1)
            manaTokenToPair("B") shouldBe (ManaColor.Black_afc9 to 1)
            manaTokenToPair("R") shouldBe (ManaColor.Red_afc9 to 1)
            manaTokenToPair("G") shouldBe (ManaColor.Green_afc9 to 1)
            manaTokenToPair("C") shouldBe (ManaColor.Colorless_afc9 to 1)
            manaTokenToPair("S") shouldBe (ManaColor.Snow_afc9 to 1)
            manaTokenToPair("X") shouldBe (ManaColor.X to 1)
        }

        test("color letter case is normalized") {
            manaTokenToPair("u") shouldBe (ManaColor.Blue_afc9 to 1)
            manaTokenToPair("g") shouldBe (ManaColor.Green_afc9 to 1)
        }

        test("positive integer maps to Generic with that count") {
            manaTokenToPair("1") shouldBe (ManaColor.Generic to 1)
            manaTokenToPair("3") shouldBe (ManaColor.Generic to 3)
            manaTokenToPair("12") shouldBe (ManaColor.Generic to 12)
        }

        test("zero, negative, empty, and unknown tokens return null") {
            manaTokenToPair("0").shouldBeNull()
            manaTokenToPair("-1").shouldBeNull()
            manaTokenToPair("").shouldBeNull()
            manaTokenToPair("Z").shouldBeNull()
            manaTokenToPair("WU").shouldBeNull()
        }
    })
