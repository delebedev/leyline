package leyline.bridge.interaction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.DevCheck
import leyline.UnitTag
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto

class UnclassifiedEntityChoicePolicyTest :
    FunSpec({
        tags(UnitTag)

        fun request(min: Int = 1) =
            PromptRequest(
                promptType = "choose_cards",
                message = "Choose",
                options = listOf("A", "B"),
                min = min,
                candidateRefs =
                    listOf(
                        PromptCandidateRefDto(0, PromptCandidateKind.Card, 10, "Hand"),
                        PromptCandidateRefDto(1, PromptCandidateKind.Card, 11, "Hand"),
                    ),
                route = PromptRouteResolver.resolve(PromptSemantic.SelectNResolution),
            )

        test("normal optional residual refuses projection with empty answer") {
            val decision =
                checkNotNull(UnclassifiedEntityChoicePolicy.decide(request(0), optional = true, allCandidatesProjectable = false))
            decision.domain shouldBe UnclassifiedEntityChoicePolicy.Domain.UnprojectableCard
            decision.indices.shouldBeEmpty()
        }

        test("normal required residual uses stable original-option prefix") {
            val decision =
                checkNotNull(UnclassifiedEntityChoicePolicy.decide(request(), optional = false, allCandidatesProjectable = false))
            decision.indices shouldBe listOf(0)
        }

        test("strict mode refuses before fallback") {
            DevCheck.init(strict = true, strictPass = false)
            try {
                shouldThrow<IllegalStateException> {
                    UnclassifiedEntityChoicePolicy.decide(request(), optional = false, allCandidatesProjectable = false)
                }
            } finally {
                DevCheck.init(strict = false, strictPass = false)
            }
        }
    })
