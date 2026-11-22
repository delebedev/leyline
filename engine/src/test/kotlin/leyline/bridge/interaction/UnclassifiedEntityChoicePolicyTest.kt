package leyline.bridge.interaction

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.DevCheck
import leyline.UnitTag
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.interaction.UnclassifiedEntityChoicePolicy.Disposition
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto

class UnclassifiedEntityChoicePolicyTest :
    FunSpec({
        tags(UnitTag)

        fun request(min: Int = 1) =
            PromptRequest(
                promptType = "choose_cards",
                message = "Choose",
                options = listOf("--PERMANENTS:--", "A", "B"),
                min = min,
                candidateRefs =
                    listOf(
                        PromptCandidateRefDto(1, PromptCandidateKind.Card, 10, "Battlefield"),
                        PromptCandidateRefDto(2, PromptCandidateKind.Player, 11),
                    ),
                route = PromptRouteResolver.resolve(PromptSemantic.SelectNResolution),
            )

        val header = listOf(false, true, true)

        test("asks over the shown options, skipping section headers") {
            UnclassifiedEntityChoicePolicy.decide(request(), false, listOf(false, true, true), header) shouldBe
                Disposition.Ask(listOf(1, 2))
        }

        test("an optional choice with one shown option is still asked") {
            UnclassifiedEntityChoicePolicy.decide(request(0), true, listOf(false, true, false), header) shouldBe
                Disposition.Ask(listOf(1))
        }

        test("a mandatory choice with one shown option answers it") {
            UnclassifiedEntityChoicePolicy.decide(request(), false, listOf(false, false, true), header) shouldBe
                Disposition.Automatic(listOf(2))
        }

        test("an optional choice with nothing shown chooses nothing") {
            UnclassifiedEntityChoicePolicy.decide(request(0), true, listOf(false, false, false), header) shouldBe
                Disposition.Automatic(emptyList())
        }

        test("a mandatory choice needing every shown option takes them and fills the count from hidden options") {
            val two = request(min = 2).copy(options = listOf("--PERMANENTS:--", "A", "B", "C"))
            assertSoftly {
                UnclassifiedEntityChoicePolicy.decide(
                    two,
                    false,
                    listOf(false, true, false, false),
                    listOf(false, true, true, true),
                ) shouldBe
                    Disposition.Automatic(listOf(1, 2))
                UnclassifiedEntityChoicePolicy.decide(
                    two,
                    false,
                    listOf(false, true, true, false),
                    listOf(false, true, true, true),
                ) shouldBe
                    Disposition.Automatic(listOf(1, 2))
                UnclassifiedEntityChoicePolicy.decide(two, false, listOf(false, true, true, true), listOf(false, true, true, true)) shouldBe
                    Disposition.Ask(listOf(1, 2, 3))
            }
        }

        test("a mandatory choice with nothing shown takes the first selectable option and fails strict runs") {
            UnclassifiedEntityChoicePolicy.decide(request(), false, listOf(false, false, false), header) shouldBe
                Disposition.Automatic(listOf(1))
            DevCheck.init(strict = true, strictPass = false)
            try {
                shouldThrow<IllegalStateException> {
                    UnclassifiedEntityChoicePolicy.decide(request(), false, listOf(false, false, false), header)
                }
            } finally {
                DevCheck.init(strict = false, strictPass = false)
            }
        }

        test("typed routes are not handled") {
            val typed = request().copy(route = PromptRouteResolver.resolve(PromptSemantic.TargetSelection))
            UnclassifiedEntityChoicePolicy.decide(typed, false, listOf(true, true, true), header) shouldBe null
        }
    })
