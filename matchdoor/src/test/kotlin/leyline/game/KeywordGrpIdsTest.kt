package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class KeywordGrpIdsTest :
    FunSpec({
        tags(UnitTag)
        test("trample resolves to 14") { KeywordGrpIds.forKeyword("Trample") shouldBe 14 }
        test("flying resolves to 8") { KeywordGrpIds.forKeyword("Flying") shouldBe 8 }
        test("hexproof resolves to 2") { KeywordGrpIds.forKeyword("Hexproof") shouldBe 2 }
        test("unknown keyword returns null") { KeywordGrpIds.forKeyword("Flanking").shouldBeNull() }
    })
