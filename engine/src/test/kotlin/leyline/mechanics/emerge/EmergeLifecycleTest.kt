package leyline.mechanics.emerge

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest

class EmergeLifecycleTest :
    SessionTest({
        test("Wretched Gryff pays Emerge through sacrifice cost prompt") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Wretched Gryff
                humanbattlefield=Island;Island;Island;Island;Walking Corpse
                humanlibrary=Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
                name = "Emerge Wretched Gryff",
                validating = true,
            )

            val gryffGrpId = harness.bridge.cardRepository.findGrpIdByName("Wretched Gryff")!!
            val emergeAbilityGrpId = harness.bridge.cardRepository.findKeywordAbilityGrpId(gryffGrpId, KeywordAbilityIds.EMERGE)!!
            val corpseIid = human.battlefield.iid("Walking Corpse")

            val payCosts =
                after {
                    castSpellByName(
                        "Wretched Gryff",
                        alternativeGrpId = emergeAbilityGrpId,
                    ).shouldBeTrue()
                }.expectOnePayCostsReq()

            assertSoftly {
                allMessages.last { it.hasPrompt() }.prompt.promptId shouldBe PromptIds.CHOOSE_OR_COST_PAY_SACRIFICE
                payCosts.effectCostReq.costSelection.idsList shouldContain corpseIid
            }

            respondToEffectCost(listOf(corpseIid))
            passUntilResolved(maxPasses = 8)

            assertSoftly {
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContain "Walking Corpse"
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .any { it.name == "Wretched Gryff" }
                    .shouldBeTrue()
            }
        }
    })
