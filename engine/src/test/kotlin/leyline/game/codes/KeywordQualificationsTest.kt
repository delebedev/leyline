package leyline.game.codes

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.GrpId
import leyline.game.codes.KeywordQualifications
import leyline.game.codes.QualificationType

class KeywordQualificationsTest :
    FunSpec({

        tags(UnitTag)

        test("Menace has known QualInfo") {
            val info = KeywordQualifications.forKeyword("Menace")
            assertSoftly {
                info.shouldNotBeNull()
                info.grpId shouldBe GrpId(142)
                info.qualificationType shouldBe QualificationType.CombatKeyword
                info.qualificationSubtype shouldBe 0
            }
        }

        test("unknown keyword returns null") {
            KeywordQualifications.forKeyword("Nonexistent").shouldBeNull()
        }

        test("knownKeywords includes Menace") {
            KeywordQualifications.knownKeywords() shouldBe setOf("Menace")
        }
    })
