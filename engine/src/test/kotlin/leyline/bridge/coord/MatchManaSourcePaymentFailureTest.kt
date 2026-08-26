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
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.ManaSourcePaymentTimeoutException
import leyline.bridge.handoff.ManaSourcePaymentWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.PrioritySignal
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.PlaybackCutReason
import leyline.game.PlaybackCutRequest
import leyline.game.PlaybackTerminalFailure
import leyline.game.state.ProjectionViewerRole
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchManaSourcePaymentFailureTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:mana source payment failures
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Savannah Lions;Coral Merfolk;Forest
            humanlibrary=Forest
            ailibrary=Mountain
            """.trimIndent()

        fun candidates(board: Board): List<Card> =
            board.human
                .getZone(ZoneType.Battlefield)
                .cards
                .filter { it.name != "Forest" }

        fun request(board: Board): PromptRequest {
            val cards = candidates(board)
            return PromptRequest(
                promptType = "choose_cards",
                message = "Choose mana sources",
                options = cards.map { it.name },
                min = 0,
                max = cards.size,
                candidateRefs =
                    cards.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Battlefield.name)
                    },
                route = PromptRouteResolver.resolve(PromptSemantic.ConvokeCost),
                waterbendManaCost = listOf(ManaColor.Generic to 1, ManaColor.White_afc9 to 1),
                waterbendCostString = "{1}{W}",
            )
        }

        fun awaitPublished(coordinator: MatchCutCoordinator): leyline.bridge.handoff.PublishedManaSourcePaymentInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.manaSourcePayments.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.manaSourcePayments.current()
            }
            return checkNotNull(published)
        }

        test("observer enqueue failure rolls back every payment feed and leaves publication state unchanged") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            coordinator.registerViewer(SeatId(2), ProjectionViewerRole.Observer)
            val playbackRequest = PlaybackCutRequest(PlaybackCutReason.PhaseChanged, 0, false)
            coordinator.feed(SeatId(1)).requestedCut = playbackRequest
            val priorProjection = board.bridge.projectionStateSnapshot()
            val priorSequence = board.bridge.committedSequence()
            coordinator.setBeforeBatchEnqueue(SeatId(2)) { _, _ -> error("observer feed unavailable") }

            shouldThrow<PlaybackTerminalFailure> {
                coordinator.manaSourcePayments.awaitPayment(request(board), candidates(board), 3_000)
            }

            assertSoftly {
                coordinator.drain(SeatId(1)) shouldBe emptyList()
                coordinator.drain(SeatId(2)) shouldBe emptyList()
                board.bridge.projectionStateSnapshot() shouldBe priorProjection
                board.bridge.committedSequence() shouldBe priorSequence
                coordinator.feed(SeatId(1)).requestedCut shouldBe playbackRequest
                coordinator.manaSourcePayments.current().shouldBeNull()
            }
        }

        test("enqueue failure retains the exact cut and preserves preexisting output") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            val existing = listOf(GREToClientMessage.getDefaultInstance())
            coordinator.enqueueCommittedBatchForTest(SeatId(1), existing)
            coordinator.setBeforeBatchEnqueue(SeatId(1)) { _, _ -> error("mana-source feed unavailable") }

            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.manaSourcePayments.awaitPayment(request(board), candidates(board), 3_000)
                }
            assertSoftly {
                terminal.cause?.message shouldBe "mana-source feed unavailable"
                terminal.pendingPromptCut
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<ManaSourcePaymentWindowValue>()
                    .candidates.size shouldBe 2
                coordinator.drain(SeatId(1)) shouldContainExactly listOf(existing)
                board.bridge.projectionStateSnapshot() shouldBe prior
                coordinator.manaSourcePayments
                    .current()
                    .shouldBeNull()
            }
        }

        test("re-prompt install failure fails the accepted command and retains its exact selection") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFailure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.manaSourcePayments.awaitPayment(request(board), candidates(board), null) }
                    .onFailure(engineFailure::set)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            coordinator.manaSourcePayments.beforeInstall = { error("mana-source re-prompt install unavailable") }
            val selected = board.instanceId(candidates(board).first().id)

            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.manaSourcePayments.select(published.interactionId, published.gameStateId, listOf(selected))
                }
            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                engineFailure.get() shouldBe terminal
                terminal.pendingPromptCut
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<ManaSourcePaymentWindowValue>()
                    .selections
                    .map { it.originalOptionIndex } shouldBe
                    listOf(0)
                coordinator.drain(SeatId(1)) shouldBe emptyList()
                board.bridge.projectionStateSnapshot() shouldBe prior
                coordinator.manaSourcePayments
                    .current()
                    .shouldBeNull()
            }
        }

        test("replacement delivery failure fails the submitter and engine with the retained cut") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFailure = AtomicReference<Throwable>()
            val engineFinished = CountDownLatch(1)
            val releaseEntered = CountDownLatch(1)
            val releaseDelivery = CountDownLatch(1)
            coordinator.manaSourcePayments.beforeDeliveryRelease = {
                releaseEntered.countDown()
                check(releaseDelivery.await(3, TimeUnit.SECONDS))
            }
            Thread {
                runCatching { coordinator.manaSourcePayments.awaitPayment(request(board), candidates(board), null) }
                    .onFailure(engineFailure::set)
                engineFinished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1)).single()
            val selected = board.instanceId(candidates(board).first().id)
            val receipt =
                coordinator.manaSourcePayments
                    .select(published.interactionId, published.gameStateId, listOf(selected))
                    .shouldNotBeNull()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var attempted = coordinator.drain(SeatId(1)).singleOrNull()
            while (attempted == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                attempted = coordinator.drain(SeatId(1)).singleOrNull()
            }
            attempted.shouldNotBeNull()
            val acknowledgementFailure = AtomicReference<Throwable>()
            val acknowledgementFinished = CountDownLatch(1)
            Thread {
                runCatching {
                    coordinator.manaSourcePayments.acknowledgeDelivery(
                        receipt.interactionId,
                        receipt.deliveryToken.shouldNotBeNull(),
                    )
                }.onFailure(acknowledgementFailure::set)
                acknowledgementFinished.countDown()
            }.start()
            releaseEntered.await(3, TimeUnit.SECONDS) shouldBe true
            val cause = IllegalStateException("mana-source delivery unavailable")
            val terminal = shouldThrow<PlaybackTerminalFailure> { coordinator.failDelivery(cause) }
            releaseDelivery.countDown()

            assertSoftly {
                acknowledgementFinished.await(3, TimeUnit.SECONDS) shouldBe true
                engineFinished.await(3, TimeUnit.SECONDS) shouldBe true
                acknowledgementFailure.get() shouldBe terminal
                engineFailure.get() shouldBe terminal
                terminal.cause shouldBe cause
                terminal.pendingPromptCut
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<ManaSourcePaymentWindowValue>()
                terminal.pendingPromptCut.shouldNotBeNull().messages shouldBe attempted
                coordinator.manaSourcePayments
                    .current()
                    .shouldBeNull()
            }
        }

        test("response wins a simultaneous timeout claim") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val timeoutEntered = CountDownLatch(1)
            val releaseTimeout = CountDownLatch(1)
            val commandEnqueued = CountDownLatch(1)
            coordinator.manaSourcePayments.beforeTimeoutClaim = {
                timeoutEntered.countDown()
                check(releaseTimeout.await(3, TimeUnit.SECONDS))
            }
            coordinator.manaSourcePayments.afterCommandEnqueue = { commandEnqueued.countDown() }
            val result = AtomicReference<List<Int>>()
            val failure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.manaSourcePayments.awaitPayment(request(board), candidates(board), 25) }
                    .onSuccess(result::set)
                    .onFailure(failure::set)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            timeoutEntered.await(3, TimeUnit.SECONDS) shouldBe true
            val selected = board.instanceId(candidates(board).first().id)
            val accepted = AtomicReference<Any?>()
            Thread {
                accepted.set(
                    coordinator.manaSourcePayments.complete(published.interactionId, published.gameStateId, listOf(selected)),
                )
            }.start()
            commandEnqueued.await(3, TimeUnit.SECONDS) shouldBe true
            releaseTimeout.countDown()

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                accepted.get().shouldNotBeNull()
                result.get() shouldContainExactly listOf(0)
                failure.get().shouldBeNull()
                coordinator.failure().shouldBeNull()
            }
        }

        test("timeout retires the window and rejects a late command") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val failure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.manaSourcePayments.awaitPayment(request(board), candidates(board), 25) }
                    .onFailure(failure::set)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            finished.await(3, TimeUnit.SECONDS) shouldBe true
            assertSoftly {
                failure.get().shouldBeInstanceOf<ManaSourcePaymentTimeoutException>()
                coordinator.manaSourcePayments
                    .complete(published.interactionId, published.gameStateId, emptyList())
                    .shouldBeNull()
                coordinator.manaSourcePayments
                    .current()
                    .shouldBeNull()
                coordinator.failure().shouldBeNull()
            }
        }

        test("bridge timeout returns the existing default and requests later progression") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val signal = PrioritySignal()
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            val bridge =
                InteractivePromptBridge(timeoutMs = 25, prioritySignal = signal).also {
                    it.runtimeBindings = coordinator.prompts.bindings(SeatId(1))
                }
            Thread {
                result.set(bridge.requestManaSourcePayment(request(board).copy(defaultIndex = 1), candidates(board)))
                finished.countDown()
            }.start()
            awaitPublished(coordinator)
            coordinator.drain(SeatId(1))

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get() shouldContainExactly listOf(1)
                signal.awaitSignal(3_000) shouldBe true
                coordinator.manaSourcePayments
                    .current()
                    .shouldBeNull()
                coordinator.failure().shouldBeNull()
            }
        }

        test("teardown wakes the engine and clears exact handles") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val failure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.manaSourcePayments.awaitPayment(request(board), candidates(board), null) }
                    .onFailure(failure::set)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            val cause = IllegalStateException("match closed")
            coordinator.shutdown(cause)

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                failure.get().shouldBeInstanceOf<PlaybackTerminalFailure>().cause shouldBe cause
                coordinator.manaSourcePayments
                    .current()
                    .shouldBeNull()
            }
            shouldThrow<PlaybackTerminalFailure> {
                coordinator.manaSourcePayments.complete(published.interactionId, published.gameStateId, emptyList())
            }
        }
    })
