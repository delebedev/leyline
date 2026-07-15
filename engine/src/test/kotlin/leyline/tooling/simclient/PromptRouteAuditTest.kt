package leyline.tooling.simclient

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.OrderRouteKind
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

@Suppress("MissingAssertSoftly")
class PromptRouteAuditTest :
    FunSpec({
        tags(UnitTag)

        test("bound AutoResolve route is not reclassified from diagnostic prompt text") {
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
            audit.findings shouldHaveSize 0
        }

        test("audit consumes the bound route even when its diagnostic semantic is Generic") {
            val audit =
                PromptRouteAuditor.audit(
                    history =
                        listOf(
                            promptRecord(
                                promptType = "choose_cards",
                                route = ResolvedPromptRoute.Order(PromptSemantic.Generic, OrderRouteKind.Bottom),
                                message = "Order cards being put into library",
                                outcome = InteractivePromptBridge.PromptCallStatus.RESPONDED,
                            ),
                        ),
                    promptHistogram = emptyMap(),
                )

            audit.findings shouldHaveSize 1
            audit.findings.single().expectedGreType shouldBe "OrderReq"
        }

        test("accepts explicit non-library order prompts as auto-resolved") {
            val audit =
                PromptRouteAuditor.audit(
                    history =
                        listOf(
                            promptRecord(
                                promptType = "choose_cards",
                                semantic = PromptSemantic.OrderGeneric,
                                message = "Order cards being put into exile",
                                outcome = InteractivePromptBridge.PromptCallStatus.RESPONDED,
                            ),
                        ),
                    promptHistogram = emptyMap(),
                )

            audit.requestsByKind shouldContainExactly mapOf("choose_cards|OrderGeneric" to 1)
            audit.findings shouldHaveSize 0
        }

        test("accepts routed SelectN semantics when SelectNReq is emitted") {
            (PromptRouteResolver.resolve(PromptSemantic.SelectNResolution) is ResolvedPromptRoute.SelectN) shouldBe true

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

        test("flags same-GRE route coverage as unverified even when the aggregate count matches") {
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
            audit.findings.map { it.bucket }.toSet() shouldBe setOf(PromptRouteAuditor.SAME_GRE_ROUTE_UNVERIFIED)
            failureClass(statsWithFindings(audit.findings)) shouldBe "natural"
        }
    })

private fun statsWithFindings(findings: List<PromptRouteFinding>): GameStats =
    GameStats(
        turn = 1,
        gameOver = true,
        iterations = 1,
        totalMessages = 1,
        promptHistogram = emptyMap(),
        hitIterCap = false,
        completionReason = "natural",
        promptRouteFindings = findings,
    )

private fun promptRecord(
    promptType: String,
    semantic: PromptSemantic = PromptSemantic.Generic,
    route: ResolvedPromptRoute = PromptRouteResolver.resolve(semantic),
    message: String = "choose cards",
    outcome: InteractivePromptBridge.PromptCallStatus,
): InteractivePromptBridge.PromptRecord =
    InteractivePromptBridge.PromptRecord(
        promptType = promptType,
        route = route,
        message = message,
        options = listOf("A", "B"),
        min = 1,
        max = 1,
        candidateCount = 0,
        outcome = outcome,
        result = listOf(0),
        callerFrames = emptyList(),
    )
