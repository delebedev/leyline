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

        test("an auto-resolved optional target group takes the offer to stop") {
            val request =
                PromptRequest(
                    promptType = "choose_one",
                    message = "Select up to another target creature or spell",
                    options = listOf("Grizzly Bears", "Lightning Bolt", "[FINISH TARGETING]"),
                    targetingFinishOptionIndex = 2,
                    route = PromptRouteResolver.resolve(PromptSemantic.Generic),
                )

            // Taking option 0 means "target this one too, ask me again", which for an
            // optional group never terminates.
            request.policyDefault()?.indices shouldBe listOf(2)
        }

        test("an auto-resolved prompt with no offer to stop keeps its declared default") {
            val request =
                PromptRequest(
                    promptType = "choose_one",
                    message = "Choose a card",
                    options = listOf("A", "B"),
                    route = PromptRouteResolver.resolve(PromptSemantic.Generic),
                )

            request.policyDefault()?.indices shouldBe listOf(0)
        }

        test("semantic is derived from the bound route") {
            val route = PromptRouteResolver.resolve(PromptSemantic.SelectNDiscard)
            val request = PromptRequest("choose_cards", "Discard", listOf("A"), route = route)

            request.route shouldBeSameInstanceAs route
            request.semantic shouldBe PromptSemantic.SelectNDiscard
        }

        test("target ownership is explicit and Generic candidates bind compatibility") {
            PromptRouteResolver.resolve(PromptSemantic.TargetSelection) shouldBe
                ResolvedPromptRoute.Targeting(PromptSemantic.TargetSelection)
            PromptRouteResolver.resolve(PromptSemantic.Generic, hasCandidateRefs = true) shouldBe
                ResolvedPromptRoute.CompatibilityCostSelection(PromptSemantic.Generic)
        }
    })
