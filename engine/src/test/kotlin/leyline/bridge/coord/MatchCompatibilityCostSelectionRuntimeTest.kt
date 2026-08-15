package leyline.bridge.coord

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.CompatibilityCostSelectionResult
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.TargetToggleValue
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.testkit.Board
import leyline.testkit.BoardTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchCompatibilityCostSelectionRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:compatibility runtime
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Forest
            humanlibrary=Forest
            ailibrary=Forest
            """.trimIndent()

        fun request(card: forge.game.card.Card): PromptRequest =
            PromptRequest(
                promptType = "choose_cards",
                message = "Choose a residual card",
                options = listOf(card.name),
                min = 1,
                max = 1,
                candidateRefs = listOf(PromptCandidateRefDto(0, PromptCandidateKind.Card, card.id, "Battlefield")),
                sourceEntityId = card.id,
                route = ResolvedPromptRoute.CompatibilityCostSelection(PromptSemantic.Generic),
            )

        fun start(board: Board): Pair<forge.game.card.Card, PromptRequest> {
            board.bridge.cutCoordinator.drain(SeatId(1))
            val card =
                board.human
                    .getZone(forge.game.zone.ZoneType.Battlefield)
                    .cards
                    .single()
            return card to request(card)
        }

        test("toggle echo and submit preserve the exact card handle") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            val (card, request) = start(board)
            val result = AtomicReference<CompatibilityCostSelectionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.compatibilityCostSelection.awaitSelection(request, listOf(card), 3_000))
                finished.countDown()
            }.start()

            var published = coordinator.compatibilityCostSelection.current()
            while (published == null) {
                Thread.onSpinWait()
                published = coordinator.compatibilityCostSelection.current()
            }
            val req =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSelectTargetsReq() }
                    .selectTargetsReq
            val iid =
                req.targetsList
                    .single()
                    .targetsList
                    .single()
                    .targetInstanceId
            val toggle =
                coordinator.compatibilityCostSelection
                    .submitToggle(
                        published.interactionId,
                        published.gameStateId,
                        published.targetIndex,
                        listOf(TargetToggleValue(iid, selected = true)),
                    ).shouldNotBeNull()
            coordinator.drain(SeatId(1)).flatten().single { it.hasSelectTargetsReq() }
            coordinator.compatibilityCostSelection.acknowledgeDelivery(
                toggle.interactionId,
                checkNotNull(toggle.deliveryToken),
            ) shouldBe
                true
            val latest = checkNotNull(coordinator.compatibilityCostSelection.current())
            val done =
                coordinator.compatibilityCostSelection
                    .submitTargets(
                        latest.interactionId,
                        latest.gameStateId,
                    ).shouldNotBeNull()
            coordinator.drain(SeatId(1)).flatten().single { it.hasSubmitTargetsResp() }
            assertSoftly {
                coordinator.compatibilityCostSelection.acknowledgeDelivery(
                    done.interactionId,
                    checkNotNull(done.deliveryToken),
                ) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().optionIndices shouldContainExactly listOf(0)
                result.get().handles.single() shouldBe card
            }
        }

        test("deadline defaults and rejects late commands") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            val (card, request) = start(board)
            val bridge =
                InteractivePromptBridge(timeoutMs = 25).also {
                    it.runtimeBindings = coordinator.prompts.bindings(SeatId(1))
                }
            val result = AtomicReference<CompatibilityCostSelectionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(bridge.requestCompatibilityCostSelection(request, listOf(card)))
                finished.countDown()
            }.start()

            var published = coordinator.compatibilityCostSelection.current()
            while (published == null) {
                Thread.onSpinWait()
                published = coordinator.compatibilityCostSelection.current()
            }
            coordinator.drain(SeatId(1))
            finished.await(3, TimeUnit.SECONDS) shouldBe true

            assertSoftly {
                result.get().optionIndices shouldContainExactly listOf(0)
                result.get().handles.single() shouldBe card
                result.get().timedOut shouldBe true
                coordinator.compatibilityCostSelection
                    .current()
                    .shouldBeNull()
                coordinator.compatibilityCostSelection
                    .submitToggle(
                        published.interactionId,
                        published.gameStateId,
                        published.targetIndex,
                        listOf(TargetToggleValue(card.id, true)),
                    ).shouldBeNull()
            }
        }
    })
