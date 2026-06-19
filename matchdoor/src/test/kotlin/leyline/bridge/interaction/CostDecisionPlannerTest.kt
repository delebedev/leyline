package leyline.bridge.interaction

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.PromptSemantic

class CostDecisionPlannerTest :
    FunSpec({
        tags(UnitTag)

        test("collect evidence plans weighted cost selection") {
            val plan = CostDecisionPlanner.collectEvidence(total = 6, manaValues = listOf(3, -1, 4))

            assertSoftly(plan) {
                semantic shouldBe PromptSemantic.SelectNCostCollectEvidence
                costSelectionWeights shouldBe listOf(3, 0, 4)
                minSelectionWeight shouldBe 6
            }
        }

        test("typed discard plans discard cost semantic") {
            assertSoftly(CostDecisionPlanner.typedDiscard()) {
                semantic shouldBe PromptSemantic.SelectNDiscard
                costSelectionWeights.shouldBeEmpty()
                minSelectionWeight shouldBe null
            }
        }

        test("enlist plans enlist cost semantic") {
            CostDecisionPlanner.enlist().semantic shouldBe PromptSemantic.EnlistCost
        }

        test("sacrifice plans sacrifice cost semantic") {
            CostDecisionPlanner.sacrifice().semantic shouldBe PromptSemantic.SelectNCostSacrifice
        }

        test("return cost plans unblocked attacker semantic from cost type") {
            CostDecisionPlanner
                .returnCost(type = "Creature.attacking+unblocked", descriptiveType = "creature")
                .semantic shouldBe PromptSemantic.ReturnUnblockedAttackerCost
        }

        test("return cost plans unblocked attacker semantic from descriptive type") {
            CostDecisionPlanner
                .returnCost(type = "Creature", descriptiveType = "Unblocked Attacker you control")
                .semantic shouldBe PromptSemantic.ReturnUnblockedAttackerCost
        }

        test("return cost stays generic for adjacent attacker cost") {
            CostDecisionPlanner
                .returnCost(type = "Creature.attacking", descriptiveType = "attacking creature")
                .semantic shouldBe PromptSemantic.Generic
        }

        test("station tap plans station semantic only for station abilities") {
            CostDecisionPlanner.tapType(isStation = true).semantic shouldBe PromptSemantic.StationTapCost
            CostDecisionPlanner.tapType(isStation = false).semantic shouldBe PromptSemantic.Generic
        }
    })
