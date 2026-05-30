package leyline.match

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateRefDto
import java.util.concurrent.CompletableFuture

class TargetingHandlerSelectNTest :
    FunSpec({
        tags(UnitTag)

        fun pendingPrompt(request: PromptRequest): InteractivePromptBridge.PendingPrompt =
            InteractivePromptBridge.PendingPrompt(
                promptId = "prompt-1",
                request = request,
                future = CompletableFuture(),
            )

        test("static SelectN response ids map through static option ids") {
            val pending =
                pendingPrompt(
                    PromptRequest(
                        promptType = "choose_type",
                        message = "Choose a creature type",
                        options = listOf("Goblin", "Human", "Kithkin"),
                        semantic = PromptSemantic.StaticSubtypeChoice,
                        staticOptionIds = listOf(34, 39, 176),
                    ),
                )
            var resolverCalls = 0

            val indices =
                TargetingHandler.mapSelectNIdsToPromptIndices(listOf(176), pending) {
                    resolverCalls += 1
                    ForgeCardId(it)
                }

            indices shouldBe listOf(2)
            resolverCalls shouldBe 0
        }

        test("non-static SelectN response ids still resolve through instance ids") {
            val pending =
                pendingPrompt(
                    PromptRequest(
                        promptType = "choose_cards",
                        message = "Choose a card",
                        options = listOf("A", "B"),
                        candidateRefs =
                            listOf(
                                PromptCandidateRefDto(index = 0, kind = "card", entityId = 10),
                                PromptCandidateRefDto(index = 1, kind = "card", entityId = 20),
                            ),
                    ),
                )

            val indices =
                TargetingHandler.mapSelectNIdsToPromptIndices(listOf(200), pending) { instanceId ->
                    mapOf(100 to ForgeCardId(10), 200 to ForgeCardId(20))[instanceId]
                }

            indices shouldBe listOf(1)
        }
    })
