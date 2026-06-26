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

        test("enlist plans semantic intent before card selection policy") {
            CostDecisionPlanner.enlistPlan(requiredCount = 1).requiredCount shouldBe 1
        }

        test("enlist materializes enlist cost semantic") {
            CostDecisionPlanner
                .enlistPlan(requiredCount = 1)
                .toCardSelectionPlan()
                .semantic shouldBe PromptSemantic.EnlistCost
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
            val intent =
                CostDecisionPlanner.returnCostPlan(
                    requiredCount = 1,
                    type = "Creature.attacking+unblocked",
                    descriptiveType = "creature",
                )

            intent.isUnblockedAttacker shouldBe true
            intent.toCardSelectionPlan().semantic shouldBe PromptSemantic.ReturnUnblockedAttackerCost
        }

        test("return cost plans unblocked attacker semantic from descriptive type") {
            val intent =
                CostDecisionPlanner.returnCostPlan(
                    requiredCount = 1,
                    type = "Creature",
                    descriptiveType = "Unblocked Attacker you control",
                )

            intent.isUnblockedAttacker shouldBe true
            intent.toCardSelectionPlan().semantic shouldBe PromptSemantic.ReturnUnblockedAttackerCost
        }

        test("return cost stays generic for adjacent attacker cost") {
            val intent =
                CostDecisionPlanner.returnCostPlan(
                    requiredCount = 1,
                    type = "Creature.attacking",
                    descriptiveType = "attacking creature",
                )

            intent.isUnblockedAttacker shouldBe false
            intent.toCardSelectionPlan().semantic shouldBe PromptSemantic.Generic
        }

        test("forage plans both payment modes when both are available") {
            val intent = CostDecisionPlanner.foragePlan(foodCount = 2, graveyardExileCount = 5)

            assertSoftly(intent) {
                canSacrificeFood shouldBe true
                canExileFromGraveyard shouldBe true
                foodSacrifice?.requiredCount shouldBe 1
                graveyardExile?.requiredCount shouldBe 3
            }
        }

        test("forage omits unavailable payment modes") {
            val intent = CostDecisionPlanner.foragePlan(foodCount = 0, graveyardExileCount = 2)

            intent.canSacrificeFood shouldBe false
            intent.canExileFromGraveyard shouldBe false
        }

        test("forage graveyard exile preserves generic prompt materialization") {
            CostDecisionPlanner
                .foragePlan(foodCount = 0, graveyardExileCount = 3)
                .graveyardExile
                ?.toCardSelectionPlan()
                ?.semantic shouldBe PromptSemantic.Generic
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
