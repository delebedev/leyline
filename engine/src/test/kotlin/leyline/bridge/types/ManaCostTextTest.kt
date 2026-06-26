package leyline.bridge.types

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

class ManaCostTextTest :
    FunSpec({
        tags(UnitTag)

        test("formats generic and WUBRG symbols") {
            ManaCostText.clientText(
                listOf(
                    ManaColor.Generic to 2,
                    ManaColor.White_afc9 to 1,
                    ManaColor.Blue_afc9 to 2,
                    ManaColor.Black_afc9 to 1,
                    ManaColor.Red_afc9 to 1,
                    ManaColor.Green_afc9 to 1,
                ),
            ) shouldBe "o2oWoUoUoBoRoG"
        }

        test("skips unsupported symbols") {
            ManaCostText.clientText(
                listOf(
                    ManaColor.Colorless_afc9 to 1,
                    ManaColor.Snow_afc9 to 1,
                    ManaColor.X to 1,
                ),
            ) shouldBe ""
        }
    })
