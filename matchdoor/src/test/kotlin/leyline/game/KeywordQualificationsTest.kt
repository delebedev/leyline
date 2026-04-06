package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class KeywordQualificationsTest :
    FunSpec({

        tags(UnitTag)

        test("Menace has known QualInfo") {
            val info = KeywordQualifications.forKeyword("Menace")
            info.shouldNotBeNull()
            info.grpId shouldBe 142
            info.qualificationType shouldBe 40
            info.qualificationSubtype shouldBe 0
        }

        test("unknown keyword returns null") {
            KeywordQualifications.forKeyword("Nonexistent").shouldBeNull()
        }

        test("knownKeywords includes Menace") {
            KeywordQualifications.knownKeywords() shouldContain "Menace"
        }
    })
