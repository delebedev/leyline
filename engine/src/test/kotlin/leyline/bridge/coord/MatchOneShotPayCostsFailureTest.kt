package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.OneShotPayCostsTimeoutException
import leyline.bridge.handoff.OneShotPayCostsWindow
import leyline.bridge.handoff.PayCostsPromptRoute
import leyline.bridge.handoff.PayCostsRouteKind
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchOneShotPayCostsFailureTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:one shot PayCosts failures
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Savannah Lions;Coral Merfolk;Forest
            humanlibrary=Mountain
            ailibrary=Forest
            """.trimIndent()

        fun cards(board: Board): List<Card> =
            board.human
                .getZone(ZoneType.Battlefield)
                .cards
                .filter { it.name != "Forest" }

        fun request(
            board: Board,
            sourceId: Int =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Forest" }
                    .id,
        ): PromptRequest {
            val candidates = cards(board)
            return PromptRequest(
                promptType = "choose_cards",
                message = "Sacrifice",
                options = candidates.map { it.name },
                min = 1,
                max = 1,
                candidateRefs =
                    candidates.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Battlefield.name)
                    },
                route =
                    ResolvedPromptRoute.PayCosts(
                        PayCostsPromptRoute(PromptSemantic.SelectNCostSacrifice, PayCostsRouteKind.Sacrifice, "sacrifice"),
                    ),
                sourceEntityId = sourceId,
            )
        }

        fun awaitPublished(coordinator: MatchCutCoordinator): leyline.bridge.handoff.PublishedOneShotPayCostsInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.oneShotPayCosts.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.oneShotPayCosts.current()
            }
            return checkNotNull(published)
        }

        test("timeout retires the exact window and rejects late selection and cancel") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val failure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.oneShotPayCosts.awaitPayment(request(board), cards(board), 25) }
                    .onFailure(failure::set)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val selected =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasPayCostsReq() }
                    .payCostsReq.effectCostReq.costSelection.idsList
                    .first()
            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                failure.get().shouldBeInstanceOf<OneShotPayCostsTimeoutException>()
                coordinator.oneShotPayCosts
                    .current()
                    .shouldBeNull()
                coordinator.oneShotPayCosts.submit(published.interactionId, published.gameStateId, listOf(selected)) shouldBe false
                coordinator.oneShotPayCosts.cancel(published.interactionId, published.gameStateId) shouldBe false
                coordinator.failure().shouldBeNull()
            }
        }

        test("capture materialize enqueue and install failures retain their exact publication stage") {
            val captureBoard = startPuzzleAtMain1(puzzle)
            captureBoard.bridge.cutCoordinator.drain(SeatId(1))
            val capture =
                shouldThrow<PlaybackTerminalFailure> {
                    captureBoard.bridge.cutCoordinator
                        .oneShotPayCosts
                        .awaitPayment(request(captureBoard), cards(captureBoard) + cards(captureBoard).first(), 3_000)
                }
            assertSoftly {
                capture.promptMaterializationDiagnostic.shouldBeNull()
                capture.pendingPromptCut.shouldBeNull()
                captureBoard.bridge.cutCoordinator.drain(SeatId(1)) shouldBe emptyList()
            }

            val materializeBoard = startPuzzleAtMain1(puzzle)
            val materializeOwner = materializeBoard.bridge.cutCoordinator
            materializeOwner.drain(SeatId(1))
            val materialize =
                shouldThrow<PlaybackTerminalFailure> {
                    materializeOwner.oneShotPayCosts.awaitPayment(
                        request(materializeBoard, Int.MAX_VALUE),
                        cards(materializeBoard),
                        3_000,
                    )
                }
            assertSoftly {
                materialize.promptMaterializationDiagnostic
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<OneShotPayCostsWindow.Select>()
                    .sourceForgeCardId
                    ?.value shouldBe
                    Int.MAX_VALUE
                materialize.pendingPromptCut.shouldBeNull()
                materializeOwner.drain(SeatId(1)) shouldBe emptyList()
            }

            val enqueueBoard = startPuzzleAtMain1(puzzle)
            val enqueueOwner = enqueueBoard.bridge.cutCoordinator
            enqueueOwner.drain(SeatId(1))
            val existing = listOf(GREToClientMessage.getDefaultInstance())
            enqueueOwner.enqueueCommittedBatchForTest(SeatId(1), existing)
            enqueueOwner.setBeforeBatchEnqueue(SeatId(1)) { _, _ -> error("PayCosts feed unavailable") }
            val enqueue =
                shouldThrow<PlaybackTerminalFailure> {
                    enqueueOwner.oneShotPayCosts.awaitPayment(request(enqueueBoard), cards(enqueueBoard), 3_000)
                }
            assertSoftly {
                enqueue.pendingPromptCut
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<OneShotPayCostsWindow>()
                enqueueOwner.drain(SeatId(1)) shouldContainExactly listOf(existing)
            }

            val installBoard = startPuzzleAtMain1(puzzle)
            val installOwner = installBoard.bridge.cutCoordinator
            installOwner.drain(SeatId(1))
            val competing =
                installBoard.bridge
                    .projectionStateSnapshot()
                    .editor()
                    .freeze()
            installOwner.prompts.settled.beforeInstall = { installBoard.bridge.replaceProjectionStateForTest(competing) }
            val install =
                shouldThrow<PlaybackTerminalFailure> {
                    installOwner.oneShotPayCosts.awaitPayment(request(installBoard), cards(installBoard), 3_000)
                }
            assertSoftly {
                install.pendingPromptCut
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<OneShotPayCostsWindow>()
                installOwner.drain(SeatId(1)) shouldBe emptyList()
                installBoard.bridge.projectionStateSnapshot() shouldBe competing
            }
        }

        test("delivery failure after drain retains the exact cut and wakes the engine") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFailure = AtomicReference<Throwable>()
            val engineFinished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.oneShotPayCosts.awaitPayment(request(board), cards(board), null) }
                    .onFailure(engineFailure::set)
                engineFinished.countDown()
            }.start()
            awaitPublished(coordinator)
            val attempted = coordinator.drain(SeatId(1)).single()
            val cause = IllegalStateException("PayCosts delivery unavailable")
            val terminal = shouldThrow<PlaybackTerminalFailure> { coordinator.failDelivery(cause) }

            assertSoftly {
                engineFinished.await(3, TimeUnit.SECONDS) shouldBe true
                engineFailure.get() shouldBe terminal
                terminal.cause shouldBe cause
                terminal.pendingPromptCut
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<OneShotPayCostsWindow>()
                terminal.pendingPromptCut.shouldNotBeNull().messages shouldBe attempted
                coordinator.oneShotPayCosts
                    .current()
                    .shouldBeNull()
            }
        }

        test("delivery retains an inner payment cut ahead of an outer blocking cut") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val blockingFailure = AtomicReference<Throwable>()
            val blockingFinished = CountDownLatch(1)
            Thread {
                runCatching {
                    coordinator.awaitOptional(
                        BlockingInteraction.Optional(ForgeCardId(cards(board).first().id), true, null, null),
                        3_000,
                        false,
                    )
                }.onFailure(blockingFailure::set)
                blockingFinished.countDown()
            }.start()
            val blockingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var blocking = coordinator.currentBlockingInteraction()
            while (blocking == null && System.nanoTime() < blockingDeadline) {
                Thread.onSpinWait()
                blocking = coordinator.currentBlockingInteraction()
            }
            checkNotNull(blocking) { "Blocking publication failed: ${blockingFailure.get()}" }

            val paymentFailure = AtomicReference<Throwable>()
            val paymentFinished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.oneShotPayCosts.awaitPayment(request(board), cards(board), null) }
                    .onFailure(paymentFailure::set)
                paymentFinished.countDown()
            }.start()
            awaitPublished(coordinator)

            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.failDelivery(IllegalStateException("delivery unavailable"))
                }

            assertSoftly {
                terminal.pendingPromptCut
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<OneShotPayCostsWindow>()
                blockingFinished.await(3, TimeUnit.SECONDS) shouldBe true
                paymentFinished.await(3, TimeUnit.SECONDS) shouldBe true
                blockingFailure.get() shouldBe terminal
                paymentFailure.get() shouldBe terminal
            }
        }

        test("teardown wakes the pending engine and clears the window") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val failure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.oneShotPayCosts.awaitPayment(request(board), cards(board), null) }
                    .onFailure(failure::set)
                finished.countDown()
            }.start()
            awaitPublished(coordinator)
            val cause = IllegalStateException("match closed")
            coordinator.shutdown(cause)
            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                failure.get() shouldBe coordinator.failure()
                coordinator.failure()?.cause shouldBe cause
                coordinator.oneShotPayCosts
                    .current()
                    .shouldBeNull()
            }
        }
    })
