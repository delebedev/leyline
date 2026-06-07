package leyline.game.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

class CardDataParsingTest :
    FunSpec({
        tags(UnitTag)

        test("parses Arena snow OldSchoolManaText token") {
            parseManaCost("oSioSi") shouldBe listOf(ManaColor.Snow_afc9 to 2)
        }
    })
