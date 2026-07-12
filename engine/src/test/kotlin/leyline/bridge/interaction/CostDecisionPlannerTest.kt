package leyline.bridge.interaction

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.PromptSemantic

class CostDecisionPlannerTest :
    FunSpec({
        tags(UnitTag)

        test("teamwork materializes weighted tap cost semantic") {
            val plan = CostDecisionPlanner.teamworkPlan(totalPower = 2, powers = listOf(3, -1, 1)).toCardSelectionPlan()

            assertSoftly(plan) {
                semantic shouldBe PromptSemantic.TeamworkCost
                costSelectionWeights shouldBe listOf(3, 0, 1)
                minSelectionWeight shouldBe 2
            }
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
