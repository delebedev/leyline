package leyline.bridge.handoff

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import leyline.UnitTag
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto

class PromptRouteBindingTest :
    FunSpec({
        tags(UnitTag)

        test("PayCosts candidate and payment re-prompts retain the original route") {
            listOf(
                PromptSemantic.ConvokeCost,
                PromptSemantic.ImproviseCost,
                PromptSemantic.WaterbendCost,
            ).forEach { semantic ->
                val route = PromptRouteResolver.resolve(semantic)
                val request =
                    PromptRequest(
                        promptType = "choose_cards",
                        message = "Choose payment sources",
                        options = listOf("A", "B"),
                        max = 2,
                        candidateRefs =
                            listOf(
                                PromptCandidateRefDto(0, PromptCandidateKind.Card, 10),
                                PromptCandidateRefDto(1, PromptCandidateKind.Card, 11),
                            ),
                        route = route,
                    )

                val rePrompt =
                    request.copy(
                        options = listOf("B"),
                        max = 1,
                        candidateRefs = listOf(PromptCandidateRefDto(0, PromptCandidateKind.Card, 11)),
                    )

                rePrompt.route shouldBeSameInstanceAs route
                rePrompt.semantic shouldBe semantic
            }
        }

        test("semantic is derived from the bound route") {
            val route = PromptRouteResolver.resolve(PromptSemantic.SelectNDiscard)
            val request = PromptRequest("choose_cards", "Discard", listOf("A"), route = route)

            request.route shouldBeSameInstanceAs route
            request.semantic shouldBe PromptSemantic.SelectNDiscard
        }

        test("target ownership is explicit and Generic candidates stay residual") {
            PromptRouteResolver.resolve(PromptSemantic.TargetSelection) shouldBe
                ResolvedPromptRoute.Targeting(PromptSemantic.TargetSelection)
            PromptRouteResolver.resolve(PromptSemantic.Generic, hasCandidateRefs = true) shouldBe
                ResolvedPromptRoute.UnclassifiedCandidate(PromptSemantic.Generic)
        }
    })
