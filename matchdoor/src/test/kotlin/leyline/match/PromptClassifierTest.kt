package leyline.match

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.UnitTag
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.PromptCandidateKind
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

        val cardRef = PromptCandidateRefDto(index = 0, kind = PromptCandidateKind.Card, entityId = 42)

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

            result.pendingPrompt.request.semantic shouldBe PromptSemantic.SelectNLegendRule
        }

        test("discard prompt classifies as select-n") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Select a card to discard",
                    semantic = PromptSemantic.SelectNDiscard,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.pendingPrompt.request.semantic shouldBe PromptSemantic.SelectNDiscard
        }

        test("resolution-time multi-pick classifies as select-n") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Choose cards",
                    semantic = PromptSemantic.SelectNResolution,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.pendingPrompt.request.semantic shouldBe PromptSemantic.SelectNResolution
        }

        test("suspect choice semantic classifies as select-n") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Choose cards",
                    semantic = PromptSemantic.SuspectChoice,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.pendingPrompt.request.semantic shouldBe PromptSemantic.SuspectChoice
        }

        test("library putback semantic classifies as select-n") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Put cards into your library",
                    semantic = PromptSemantic.SelectNLibraryPutback,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.pendingPrompt.request.semantic shouldBe PromptSemantic.SelectNLibraryPutback
        }

        test("sacrifice cost semantic classifies as select-n") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Choose permanents",
                    semantic = PromptSemantic.SelectNCostSacrifice,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.pendingPrompt.request.semantic shouldBe PromptSemantic.SelectNCostSacrifice
        }

        test("sacrifice effect semantic classifies as select-n") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Choose a creature",
                    semantic = PromptSemantic.SelectNSacrificeEffect,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.pendingPrompt.request.semantic shouldBe PromptSemantic.SelectNSacrificeEffect
        }

        test("station tap-cost semantic classifies as station cost payment") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Tap a creature to add charge counters",
                    semantic = PromptSemantic.StationTapCost,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.pendingPrompt.request.semantic shouldBe PromptSemantic.StationTapCost
        }

        test("collect evidence semantic classifies as weighted cost payment") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Exile cards with total mana value 6 or greater",
                    semantic = PromptSemantic.SelectNCostCollectEvidence,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.pendingPrompt.request.semantic shouldBe PromptSemantic.SelectNCostCollectEvidence
        }

        test("return-unblocked-attacker semantic classifies as cost payment") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Return an unblocked attacker",
                    semantic = PromptSemantic.ReturnUnblockedAttackerCost,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.pendingPrompt.request.semantic shouldBe PromptSemantic.ReturnUnblockedAttackerCost
        }

        test("convoke semantic classifies as cost payment") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Choose creatures to tap for convoke",
                    semantic = PromptSemantic.ConvokeCost,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.pendingPrompt.request.semantic shouldBe PromptSemantic.ConvokeCost
        }

        test("learn semantic classifies as select-n") {
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Learn a Lesson",
                    semantic = PromptSemantic.LearnLesson,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.pendingPrompt.request.semantic shouldBe PromptSemantic.LearnLesson
        }

        test("static color semantic classifies as select-n") {
            val result =
                classify(
                    promptType = "choose_colors",
                    message = "Choose a color",
                    semantic = PromptSemantic.StaticColorChoice,
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.pendingPrompt.request.semantic shouldBe PromptSemantic.StaticColorChoice
        }

        test("static subtype semantic classifies as select-n") {
            val result =
                classify(
                    promptType = "choose_type",
                    message = "Choose a creature type",
                    semantic = PromptSemantic.StaticSubtypeChoice,
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.pendingPrompt.request.semantic shouldBe PromptSemantic.StaticSubtypeChoice
        }

        test("static parity semantic classifies as select-n") {
            val result =
                classify(
                    promptType = "confirm",
                    message = "Odd or even",
                    semantic = PromptSemantic.StaticParityChoice,
                ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.pendingPrompt.request.semantic shouldBe PromptSemantic.StaticParityChoice
        }

        test("library order semantic classifies as order even with card refs") {
            val bottom =
                classify(
                    promptType = "order",
                    message = "Order cards",
                    semantic = PromptSemantic.OrderForBottom,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.Order>()
            val top =
                classify(
                    promptType = "order",
                    message = "Order cards",
                    semantic = PromptSemantic.OrderForTop,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.Order>()

            bottom.pendingPrompt.request.semantic shouldBe PromptSemantic.OrderForBottom
            top.pendingPrompt.request.semantic shouldBe PromptSemantic.OrderForTop
        }

        test("generic order semantic auto-resolves until a non-library prompt shape exists") {
            val result =
                classify(
                    promptType = "order",
                    message = "Order cards",
                    semantic = PromptSemantic.OrderGeneric,
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.AutoResolve>()

            result.pendingPrompt.request.semantic shouldBe PromptSemantic.OrderGeneric
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
            val result =
                classify(
                    promptType = "choose_cards",
                    message = "Choose target creature",
                    candidateRefs = listOf(cardRef),
                ).shouldBeInstanceOf<ClassifiedPrompt.Targeting>()

            result.pendingPrompt.request.candidateRefs shouldBe listOf(cardRef)
        }

        test("plain prompt without candidate refs classifies as auto-resolve") {
            classify(
                promptType = "confirm",
                message = "Discard to hand size",
            ).shouldBeInstanceOf<ClassifiedPrompt.AutoResolve>()
        }
    })
