package leyline.match

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.UnitTag
import leyline.bridge.InteractivePromptBridge
import leyline.bridge.PromptCandidateRefDto
import leyline.bridge.PromptRequest
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import java.util.concurrent.CompletableFuture

class PromptClassifierTest :
    FunSpec({

        tags(UnitTag)

        fun classify(
            promptType: String,
            message: String,
            candidateRefs: List<PromptCandidateRefDto> = emptyList(),
        ): ClassifiedPrompt {
            val prompt = InteractivePromptBridge.PendingPrompt(
                promptId = "p1",
                request = PromptRequest(
                    promptType = promptType,
                    message = message,
                    options = listOf("A", "B"),
                    candidateRefs = candidateRefs,
                ),
                future = CompletableFuture(),
            )
            return PromptClassifier.classify(prompt)
        }

        val cardRef = PromptCandidateRefDto(index = 0, kind = "card", entityId = 42)

        test("surveil prompt classifies as grouping before generic targeting") {
            val result = classify(
                promptType = "confirm",
                message = "Surveil: look at the top card",
                candidateRefs = listOf(cardRef),
            ).shouldBeInstanceOf<ClassifiedPrompt.Grouping>()

            result.context shouldBe GroupingContext.Surveil
        }

        test("scry prompt classifies as grouping") {
            val result = classify(
                promptType = "choose_cards",
                message = "Scry: arrange the top cards",
                candidateRefs = listOf(cardRef),
            ).shouldBeInstanceOf<ClassifiedPrompt.Grouping>()

            result.context shouldBe GroupingContext.Scry_a0f6
        }

        test("modal prompt classifies as modal choice") {
            classify(
                promptType = "modal",
                message = "Choose mode for Charming Prince",
            ).shouldBeInstanceOf<ClassifiedPrompt.ModalChoice>()
        }

        test("legend rule prompt classifies as select-n") {
            val result = classify(
                promptType = "legend_rule",
                message = "Choose one",
                candidateRefs = listOf(cardRef),
            ).shouldBeInstanceOf<ClassifiedPrompt.SelectN>()

            result.reason shouldBe ClassifiedPrompt.SelectN.Reason.LegendRule
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
