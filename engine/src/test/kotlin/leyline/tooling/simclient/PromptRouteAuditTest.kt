package leyline.tooling.simclient

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.OrderRouteKind
import leyline.bridge.handoff.PromptCallStatus
import leyline.bridge.handoff.PromptRecord
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
                                outcome = PromptCallStatus.RESPONDED,
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
                                outcome = PromptCallStatus.RESPONDED,
                            ),
                        ),
                    promptHistogram = emptyMap(),
                )

            audit.findings shouldHaveSize 1
            audit.findings.single().expectedGreType shouldBe "OrderReq"
        }

        test("accepts routed SelectN semantics when SelectNReq is emitted") {
            (
                PromptRouteResolver.resolve(PromptSemantic.SelectNResolution) is
                    ResolvedPromptRoute.UnclassifiedEntityChoice
            ) shouldBe true

            val audit =
                PromptRouteAuditor.audit(
                    history =
                        listOf(
                            promptRecord(
                                promptType = "choose_cards",
                                semantic = PromptSemantic.SelectNResolution,
                                outcome = PromptCallStatus.RESPONDED,
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
                                outcome = PromptCallStatus.TIMEOUT,
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
                                outcome = PromptCallStatus.RESPONDED,
                            ),
                            promptRecord(
                                promptType = "choose_cards",
                                semantic = PromptSemantic.SelectNDiscard,
                                outcome = PromptCallStatus.RESPONDED,
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
                                outcome = PromptCallStatus.RESPONDED,
                            ),
                            promptRecord(
                                promptType = "choose_cards",
                                semantic = PromptSemantic.SelectNDiscard,
                                outcome = PromptCallStatus.RESPONDED,
                            ),
                        ),
                    promptHistogram = mapOf(GREMessageType.SelectNreq to 2),
                )

            audit.findings shouldHaveSize 2
            audit.findings.map { it.bucket }.toSet() shouldBe setOf(PromptRouteAuditor.SAME_GRE_ROUTE_UNVERIFIED)
            failureClass(statsWithFindings(audit.findings)) shouldBe "natural"
        }

        test("groups unnamed default answers by the Forge effect that asked") {
            val explore = listOf("PlayerControllerHuman#confirmAction:10", "ExploreEffect#resolve:57", "SpellAbilityEffect#x:1")
            val sacrifice = listOf("SacrificeEffect#resolve:80", "AbilityUtils#resolve:2")
            val audit =
                PromptRouteAuditor.audit(
                    history = emptyList(),
                    promptHistogram = emptyMap(),
                    defaults =
                        listOf(
                            promptRecord("confirm", outcome = PromptCallStatus.DEFAULTED_POLICY, callerFrames = explore),
                            promptRecord(
                                "confirm",
                                outcome = PromptCallStatus.DEFAULTED_POLICY,
                                callerFrames = explore.map { it.replace(":57", ":61") },
                            ),
                            promptRecord("confirm", outcome = PromptCallStatus.DEFAULTED_POLICY, callerFrames = sacrifice),
                            promptRecord(
                                "confirm",
                                outcome = PromptCallStatus.DEFAULTED_POLICY,
                                callerFrames = sacrifice,
                                options = listOf("Yes"),
                            ),
                        ),
                )

            audit.unnamedDefaultAnswers.map { it.askedBy to it.count } shouldBe
                listOf("ExploreEffect#resolve" to 2, "SacrificeEffect#resolve" to 1)
        }

        test("evicted defaults are reported so a strict run cannot pass an incomplete audit") {
            val audit =
                PromptRouteAuditor.audit(
                    history = emptyList(),
                    promptHistogram = emptyMap(),
                    defaults = emptyList(),
                    droppedDefaults = 3,
                )

            audit.unnamedDefaultAnswers.map { it.askedBy to it.count } shouldBe listOf(PromptRouteAuditor.DROPPED_DEFAULTS to 3)
            isStrictFailure(statsWithFindings(emptyList()).copy(unnamedDefaultAnswers = audit.unnamedDefaultAnswers)) shouldBe true
        }

        test("names cost and handler callers when no effect frame is present") {
            PromptRouteAuditor.askedBy(listOf("PlayerControllerHuman#x:1", "HumanCostDecision#visit:4")) shouldBe
                "HumanCostDecision#visit"
            PromptRouteAuditor.askedBy(listOf("GameAction#moveTo:9")) shouldBe "GameAction#moveTo"
            PromptRouteAuditor.askedBy(emptyList()) shouldBe "unknown"
        }

        test("an unnamed default answer fails a strict run without changing the failure class") {
            val answers =
                PromptRouteAuditor.unnamedDefaultAnswers(
                    listOf(promptRecord("confirm", outcome = PromptCallStatus.DEFAULTED_POLICY)),
                )
            val stats = statsWithFindings(emptyList()).copy(unnamedDefaultAnswers = answers)

            failureClass(stats) shouldBe "natural"
            isStrictFailure(stats) shouldBe true
            isStrictFailure(statsWithFindings(emptyList())) shouldBe false
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
    outcome: PromptCallStatus,
    callerFrames: List<String> = emptyList(),
    options: List<String> = listOf("A", "B"),
): PromptRecord =
    PromptRecord(
        promptType = promptType,
        route = route,
        message = message,
        options = options,
        min = 1,
        max = 1,
        candidateCount = 0,
        outcome = outcome,
        result = listOf(0),
        callerFrames = callerFrames,
    )
