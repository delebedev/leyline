package leyline.match

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptResponseMapper
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind
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

        test("non-static SelectN response ids still resolve through instance ids") {
            val pending =
                pendingPrompt(
                    PromptRequest(
                        promptType = "choose_cards",
                        message = "Choose a card",
                        options = listOf("A", "B"),
                        candidateRefs =
                            listOf(
                                PromptCandidateRefDto(index = 0, kind = PromptCandidateKind.Card, entityId = 10),
                                PromptCandidateRefDto(index = 1, kind = PromptCandidateKind.Card, entityId = 20),
                            ),
                    ),
                )

            val indices =
                TargetingHandler.mapSelectNIdsToPromptIndices(listOf(200), pending) { instanceId ->
                    mapOf(100 to ForgeCardId(10), 200 to ForgeCardId(20))[instanceId]
                }

            indices shouldBe listOf(1)
        }

        test("candidate refs map back to original sparse option indices") {
            val pending =
                pendingPrompt(
                    PromptRequest(
                        promptType = "choose_cards",
                        message = "Choose a target",
                        options = listOf("--CARDS ON BATTLEFIELD:--", "A", "[FINISH TARGETING]"),
                        candidateRefs = listOf(PromptCandidateRefDto(index = 1, kind = PromptCandidateKind.Card, entityId = 10)),
                    ),
                )

            val indices =
                TargetingHandler.mapSelectNIdsToPromptIndices(listOf(100), pending) { instanceId ->
                    mapOf(100 to ForgeCardId(10))[instanceId]
                }

            indices shouldBe listOf(1)
        }

        test("target mapping keeps card and player ids separate") {
            val request =
                PromptRequest(
                    promptType = "target",
                    message = "Choose target",
                    options = listOf("Player", "Creature"),
                    candidateRefs =
                        listOf(
                            PromptCandidateRefDto(index = 0, kind = PromptCandidateKind.Player, entityId = 10),
                            PromptCandidateRefDto(index = 1, kind = PromptCandidateKind.Card, entityId = 10),
                        ),
                )

            val indices =
                PromptResponseMapper.targetIdsToPromptIndices(
                    instanceIds = listOf(200),
                    request = request,
                    resolveForgeCardId = { ForgeCardId(10) },
                    resolvePlayerEntityId = { null },
                )

            indices shouldBe listOf(1)
        }
    })
