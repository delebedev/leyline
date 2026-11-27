package leyline.tooling.simclient

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class SimClientConfigTest :
    FunSpec({

        tags(UnitTag)

        test("an ability row that disagrees with its ability fails a strict run without changing the failure class") {
            val stats = naturalGame().copy(abilityRowMismatches = mapOf("trigger/Card: Annihilator keyword on row 8" to 1))

            assertSoftly {
                failureClass(stats) shouldBe "natural"
                isStrictFailure(stats) shouldBe true
                isStrictFailure(naturalGame()) shouldBe false
            }
        }

        test("an action catalog with indistinguishable offers fails a strict run without changing the failure class") {
            val stats = naturalGame().copy(ambiguousActionCatalogs = mapOf("Activate_add3 grpId=1 abilityGrpId=2 uniqueAbilityId=0" to 1))

            failureClass(stats) shouldBe "natural"
            isStrictFailure(stats) shouldBe true
        }
    })

private fun naturalGame(): GameStats =
    GameStats(
        turn = 1,
        gameOver = true,
        iterations = 1,
        totalMessages = 1,
        promptHistogram = emptyMap(),
        hitIterCap = false,
        completionReason = "natural",
    )
