package leyline.simclient

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptSemantic
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

class PromptRouteAuditTest :
    FunSpec({
        tags(UnitTag)

        test("flags generic order prompts when no OrderReq is emitted") {
            val audit =
                PromptRouteAuditor.audit(
                    history =
                        listOf(
                            promptRecord(
                                promptType = "order",
                                semantic = PromptSemantic.Generic,
                                outcome = InteractivePromptBridge.PromptCallStatus.RESPONDED,
                            ),
                        ),
                    promptHistogram = emptyMap(),
                )

            audit.requestsByKind shouldContainExactly mapOf("order|Generic" to 1)
            audit.findings shouldHaveSize 1
            audit.findings.single().bucket shouldBe "swallowed_auto_resolve"
            audit.findings.single().expectedGreType shouldBe "OrderReq"
        }

        test("flags Forge library-order choose-cards prompts as missing OrderReq") {
            val audit =
                PromptRouteAuditor.audit(
                    history =
                        listOf(
                            promptRecord(
                                promptType = "choose_cards",
                                semantic = PromptSemantic.Generic,
                                message = "Order cards being put into library",
                                outcome = InteractivePromptBridge.PromptCallStatus.RESPONDED,
                            ),
                        ),
                    promptHistogram = emptyMap(),
                )

            audit.findings shouldHaveSize 1
            audit.findings.single().expectedGreType shouldBe "OrderReq"
        }

        test("accepts routed SelectN semantics when SelectNReq is emitted") {
            val audit =
                PromptRouteAuditor.audit(
                    history =
                        listOf(
                            promptRecord(
                                promptType = "choose_cards",
                                semantic = PromptSemantic.SelectNResolution,
                                outcome = InteractivePromptBridge.PromptCallStatus.RESPONDED,
                            ),
                        ),
                    promptHistogram = mapOf(GREMessageType.SelectNreq to 1),
                )

            audit.requestsByKind shouldContainExactly mapOf("choose_cards|SelectNResolution" to 1)
            audit.findings shouldHaveSize 0
        }

        test("classifies prompt timeout before emitted route as defaulted timeout") {
            val audit =
                PromptRouteAuditor.audit(
                    history =
                        listOf(
                            promptRecord(
                                promptType = "choose_cards",
                                semantic = PromptSemantic.SelectNDiscard,
                                outcome = InteractivePromptBridge.PromptCallStatus.TIMEOUT,
                            ),
                        ),
                    promptHistogram = emptyMap(),
                )

            audit.findings shouldHaveSize 1
            audit.findings.single().bucket shouldBe "defaulted_timeout"
            audit.findings.single().expectedGreType shouldBe "SelectNReq"
        }

        test("flags aggregate shortfall when one emitted route covers fewer prompts than expected") {
            val audit =
                PromptRouteAuditor.audit(
                    history =
                        listOf(
                            promptRecord(
                                promptType = "choose_cards",
                                semantic = PromptSemantic.SelectNResolution,
                                outcome = InteractivePromptBridge.PromptCallStatus.RESPONDED,
                            ),
                            promptRecord(
                                promptType = "choose_cards",
                                semantic = PromptSemantic.SelectNDiscard,
                                outcome = InteractivePromptBridge.PromptCallStatus.RESPONDED,
                            ),
                        ),
                    promptHistogram = mapOf(GREMessageType.SelectNreq to 1),
                )

            audit.findings shouldHaveSize 2
        }

        test("flags ambiguous same-GRE route coverage") {
            val audit =
                PromptRouteAuditor.audit(
                    history =
                        listOf(
                            promptRecord(
                                promptType = "choose_cards",
                                semantic = PromptSemantic.SelectNResolution,
                                outcome = InteractivePromptBridge.PromptCallStatus.RESPONDED,
                            ),
                            promptRecord(
                                promptType = "choose_cards",
                                semantic = PromptSemantic.SelectNDiscard,
                                outcome = InteractivePromptBridge.PromptCallStatus.RESPONDED,
                            ),
                        ),
                    promptHistogram = mapOf(GREMessageType.SelectNreq to 2),
                )

            audit.findings shouldHaveSize 2
            audit.findings.map { it.bucket }.toSet() shouldBe setOf("ambiguous_route_coverage")
        }
    })

private fun promptRecord(
    promptType: String,
    semantic: PromptSemantic,
    message: String = "choose cards",
    outcome: InteractivePromptBridge.PromptCallStatus,
): InteractivePromptBridge.PromptRecord =
    InteractivePromptBridge.PromptRecord(
        promptType = promptType,
        semantic = semantic,
        message = message,
        options = listOf("A", "B"),
        min = 1,
        max = 1,
        candidateCount = 0,
        outcome = outcome,
        result = listOf(0),
        callerFrames = emptyList(),
    )
