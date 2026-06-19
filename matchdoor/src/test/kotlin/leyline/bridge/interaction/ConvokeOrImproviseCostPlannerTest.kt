package leyline.bridge.interaction

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.PromptSemantic

class ConvokeOrImproviseCostPlannerTest :
    FunSpec({
        tags(UnitTag)

        test("artifacts and creatures plan waterbend policy") {
            val plan = planFor(artifacts = true, creatures = true, optionCount = 5, maxReduction = 3)

            assertSoftly(plan) {
                keyword shouldBe "waterbend"
                semantic shouldBe PromptSemantic.WaterbendCost
                maxSelection shouldBe 3
                candidateRefsPolicy shouldBe CandidateRefsPolicy.Selectable
                manaFieldsPolicy shouldBe CostManaFieldsPolicy.IncludeNativePaymentCost
                convokePaymentRecordPolicy shouldBe ConvokePaymentRecordPolicy.None
            }
        }

        test("creatures without artifacts plan convoke policy") {
            val plan = planFor(artifacts = false, creatures = true, optionCount = 4, maxReduction = null)

            assertSoftly(plan) {
                keyword shouldBe "convoke"
                semantic shouldBe PromptSemantic.ConvokeCost
                maxSelection shouldBe 4
                candidateRefsPolicy shouldBe CandidateRefsPolicy.Selectable
                manaFieldsPolicy shouldBe CostManaFieldsPolicy.IncludeNativePaymentCost
                convokePaymentRecordPolicy shouldBe ConvokePaymentRecordPolicy.Record
            }
        }

        test("artifacts without creatures plan improvise policy") {
            val plan = planFor(artifacts = true, creatures = false, optionCount = 6, maxReduction = 2)

            assertSoftly(plan) {
                keyword shouldBe "improvise"
                semantic shouldBe PromptSemantic.Generic
                maxSelection shouldBe 2
                candidateRefsPolicy shouldBe CandidateRefsPolicy.None
                manaFieldsPolicy shouldBe CostManaFieldsPolicy.None
                convokePaymentRecordPolicy shouldBe ConvokePaymentRecordPolicy.None
            }
        }

        test("max selection is capped by option count") {
            planFor(artifacts = false, creatures = true, optionCount = 2, maxReduction = 5).maxSelection shouldBe 2
        }

        test("non artifact non creature fallback preserves convoke label without native mana fields") {
            val plan = planFor(artifacts = false, creatures = false, optionCount = 3, maxReduction = null)

            assertSoftly(plan) {
                keyword shouldBe "convoke"
                semantic shouldBe PromptSemantic.Generic
                candidateRefsPolicy shouldBe CandidateRefsPolicy.None
                manaFieldsPolicy shouldBe CostManaFieldsPolicy.None
                convokePaymentRecordPolicy shouldBe ConvokePaymentRecordPolicy.None
            }
        }
    })

private fun planFor(
    artifacts: Boolean,
    creatures: Boolean,
    optionCount: Int,
    maxReduction: Int?,
): ConvokeOrImproviseCostPlan =
    ConvokeOrImproviseCostPlanner.plan(
        optionCount = optionCount,
        maxReduction = maxReduction,
        artifacts = artifacts,
        creatures = creatures,
    )
