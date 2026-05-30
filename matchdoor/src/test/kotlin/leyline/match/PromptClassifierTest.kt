package leyline.match

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.UnitTag
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.PromptCandidateRefDto
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import java.util.concurrent.CompletableFuture

class PromptClassifierTest :
    FunSpec({

        tags(UnitTag)

        fun classify(
            promptType: String,
            message: String,
            semantic: PromptSemantic = PromptSemantic.Generic,
            candidateRefs: List<PromptCandidateRefDto> = emptyList(),
        ): ClassifiedPrompt {
            val prompt =
                InteractivePromptBridge.PendingPrompt(
                    promptId = "p1",
                    request =
                        PromptRequest(
                            promptType = promptType,
                            message = message,
                            options = listOf("A", "B"),
                            semantic = semantic,
                            candidateRefs = candidateRefs,
                        ),
                    future = CompletableFuture(),
                )
            return PromptClassifier.classify(prompt)
        }

        val cardRef = PromptCandidateRefDto(index = 0, kind = "card", entityId = 42)

        test("surveil prompt classifies as grouping before generic targeting") {
            val result =
                classify(
                    promptType = "confirm",
                    message = "anything",
                    semantic = PromptSemantic.GroupingSurveil,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.Grouping>()

            result.context shouldBe GroupingContext.Surveil
        }

        test("scry prompt classifies as grouping") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "anything",
                    semantic = PromptSemantic.GroupingScry,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.Grouping>()

            result.context shouldBe GroupingContext.Scry_a0f6
        }

        test("modal prompt classifies as modal choice") {
            classify(
                promptType = "modal",
                message = "Choose mode for Charming Prince",
                semantic = PromptSemantic.ModalChoice,
            ).shouldBeInstanceOf<ClassifiedPrompt.ModalChoice>()
        }

        test("legend rule prompt classifies as select-n") {
            val result =
                classify(
                    promptType = "legend_rule",
                    message = "Choose one",
                    semantic = PromptSemantic.SelectNLegendRule,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.reason shouldBe ClassifiedPrompt.SelectN.Reason.LegendRule
        }

        test("discard cost prompt classifies as select-n with Discard reason") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Select a card to discard",
                    semantic = PromptSemantic.SelectNDiscard,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.reason shouldBe ClassifiedPrompt.SelectN.Reason.Discard
        }

        test("resolution-time multi-pick classifies as select-n with Resolution reason") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Choose cards",
                    semantic = PromptSemantic.SelectNResolution,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.reason shouldBe ClassifiedPrompt.SelectN.Reason.Resolution
        }

        test("sacrifice cost semantic classifies as select-n with Sacrifice reason") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Choose permanents",
                    semantic = PromptSemantic.SelectNCostSacrifice,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.reason shouldBe ClassifiedPrompt.SelectN.Reason.Sacrifice
        }

        test("sacrifice effect semantic classifies as select-n without cost-payment reason") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Choose a creature",
                    semantic = PromptSemantic.SelectNSacrificeEffect,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.reason shouldBe ClassifiedPrompt.SelectN.Reason.SacrificeEffect
        }

        test("station tap-cost semantic classifies as station cost payment") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Tap a creature to add charge counters",
                    semantic = PromptSemantic.StationTapCost,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.reason shouldBe ClassifiedPrompt.SelectN.Reason.StationTapCost
        }

        test("collect evidence semantic classifies as weighted cost payment") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Exile cards with total mana value 6 or greater",
                    semantic = PromptSemantic.SelectNCostCollectEvidence,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.reason shouldBe ClassifiedPrompt.SelectN.Reason.CollectEvidenceCost
        }

        test("return-unblocked-attacker semantic classifies as cost payment") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Return an unblocked attacker",
                    semantic = PromptSemantic.ReturnUnblockedAttackerCost,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.reason shouldBe ClassifiedPrompt.SelectN.Reason.ReturnUnblockedAttackerCost
        }

        test("learn semantic classifies as select-n with LearnLesson reason") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Learn a Lesson",
                    semantic = PromptSemantic.LearnLesson,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.reason shouldBe ClassifiedPrompt.SelectN.Reason.LearnLesson
        }

        test("generic choose-cards prompt does not infer sacrifice from message text") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Sacrifice a creature",
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.Targeting>()

            result.pendingPrompt.request.semantic shouldBe PromptSemantic.Generic
        }

        test("candidate refs without a stronger semantic classifies as targeting") {
            classify(
                promptType = "choose_cards",
                message = "Choose target creature",
                candidateRefs = listOf(cardRef),
            ).shouldBeInstanceOf<ClassifiedPrompt.Targeting>()
        }

        test("plain prompt without candidate refs classifies as auto-resolve") {
            classify(
                promptType = "confirm",
                message = "Discard to hand size",
            ).shouldBeInstanceOf<ClassifiedPrompt.AutoResolve>()
        }
    })
