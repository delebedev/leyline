package leyline.bridge.interaction

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.PromptSemantic

class CostDecisionPlannerTest :
    FunSpec({
        tags(UnitTag)

        test("collect evidence plans semantic intent before card selection policy") {
            val intent = CostDecisionPlanner.collectEvidencePlan(total = 6, manaValues = listOf(3, -1, 4))

            intent.total shouldBe 6
            intent.manaValues shouldBe listOf(3, -1, 4)
        }

        test("collect evidence materializes weighted cost selection") {
            val plan = CostDecisionPlanner.collectEvidencePlan(total = 6, manaValues = listOf(3, -1, 4)).toCardSelectionPlan()

            assertSoftly(plan) {
                semantic shouldBe PromptSemantic.SelectNCostCollectEvidence
                costSelectionWeights shouldBe listOf(3, 0, 4)
                minSelectionWeight shouldBe 6
            }
        }

        test("typed discard plans semantic intent before card selection policy") {
            val intent = CostDecisionPlanner.discardPlan(requiredCount = 2, discardType = "Creature")

            intent.requiredCount shouldBe 2
            intent.discardType shouldBe "Creature"
        }

        test("typed discard materializes discard cost semantic") {
            CostDecisionPlanner
                .discardPlan(requiredCount = 2, discardType = "Creature")
                .toCardSelectionPlan()
                .semantic shouldBe PromptSemantic.SelectNDiscard
        }

        test("enlist plans enlist cost semantic") {
            CostDecisionPlanner.enlist().semantic shouldBe PromptSemantic.EnlistCost
        }

        test("sacrifice plans semantic intent before card selection policy") {
            val intent = CostDecisionPlanner.sacrificePlan(requiredCount = 2, differentNames = true)

            intent.requiredCount shouldBe 2
            intent.differentNames shouldBe true
        }

        test("sacrifice materializes sacrifice cost semantic") {
            CostDecisionPlanner
                .sacrificePlan(requiredCount = 2)
                .toCardSelectionPlan()
                .semantic shouldBe PromptSemantic.SelectNCostSacrifice
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

        test("tap type plans semantic intent before card selection policy") {
            val intent = CostDecisionPlanner.tapTypePlan(minSelection = 1, maxSelection = 3, isStation = true)

            assertSoftly(intent) {
                minSelection shouldBe 1
                maxSelection shouldBe 3
                isStation shouldBe true
            }
        }

        test("station tap materializes station semantic only for station abilities") {
            CostDecisionPlanner
                .tapTypePlan(minSelection = 1, maxSelection = 3, isStation = true)
                .toCardSelectionPlan()
                .semantic shouldBe PromptSemantic.StationTapCost
            CostDecisionPlanner
                .tapTypePlan(minSelection = 1, maxSelection = 3, isStation = false)
                .toCardSelectionPlan()
                .semantic shouldBe PromptSemantic.Generic
        }
    })
