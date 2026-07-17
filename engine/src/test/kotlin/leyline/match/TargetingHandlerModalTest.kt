package leyline.match

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.SeatId
import leyline.testkit.BoardTest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class TargetingHandlerModalTest :
    BoardTest({

        test("modal without full-list metadata preserves repository child order") {
            val (bridge, _, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Trufflesnout", human, ZoneType.Library)
                }
            val ops = SessionTraceOps(gameBridge = bridge, counter = counter)
            val handler = TargetingHandler(sink = ops, counters = ops, bundles = ops, ctx = ops.ctx)
            val promptBridge = bridge.promptBridge(SeatId(1))
            val response =
                CompletableFuture.supplyAsync {
                    promptBridge.requestChoice(
                        PromptRequest(
                            promptType = "choose_one",
                            message = "Choose a mode",
                            options = listOf("Counter", "Life"),
                            min = 1,
                            max = 1,
                            route = PromptRouteResolver.resolve(PromptSemantic.ModalChoice),
                            modalSourceCardName = "Trufflesnout",
                        ),
                    )
                }
            val pending = promptBridge.awaitPendingPrompt()

            handler.checkPendingPrompt() shouldBe TargetingHandler.PromptResult.SENT_TO_CLIENT

            val modalReq =
                ops.sentGRE
                    .flatten()
                    .single { it.hasCastingTimeOptionsReq() }
                    .castingTimeOptionsReq
                    .getCastingTimeOptionReq(0)
                    .modalReq
            val emittedGrpIds = modalReq.modalOptionsList.map { it.grpId }
            assertSoftly {
                emittedGrpIds shouldContainExactly listOf(60590, 121504)
                modalReq.modalOptionsList.all { it.modeCostCount == 0 }.shouldBeTrue()
                modalReq.excludedOptionsCount shouldBe 0
            }

            val selectedIndices = TargetingHandler.mapModalGrpIdsToPromptIndices(listOf(121504), emittedGrpIds)
            promptBridge.submitResponse(pending.promptId, selectedIndices).shouldBeTrue()
            response.get(5, TimeUnit.SECONDS) shouldContainExactly listOf(1)
        }
    })
