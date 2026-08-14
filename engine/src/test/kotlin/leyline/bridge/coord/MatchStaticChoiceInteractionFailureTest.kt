package leyline.bridge.coord

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PublishedStaticChoiceInteraction
import leyline.bridge.handoff.StaticChoiceInteractionTimeoutException
import leyline.bridge.types.SeatId
import leyline.bridge.types.StaticChoiceIds
import leyline.game.PlaybackTerminalFailure
import leyline.game.bundle.BundleBuilder
import leyline.game.state.GameBridge
import leyline.match.GreMessageSink
import leyline.match.drainOneCoordinatorBarrier
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ResultReason
import wotc.mtgo.gre.external.messaging.Messages.StaticList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchStaticChoiceInteractionFailureTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:static choice failures
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Island
            humanlibrary=Forest
            ailibrary=Forest
            """.trimIndent()

        val values = listOf(StaticChoiceIds.colorIdForName("Red")!!, StaticChoiceIds.colorIdForName("Blue")!!)

        fun sourceId(board: Board): Int =
            board.human
                .getZone(ZoneType.Battlefield)
                .cards
                .single()
                .id

        fun request(
            board: Board,
            source: Int? = sourceId(board),
            max: Int = 1,
        ): PromptRequest =
            PromptRequest(
                promptType = "choose_one",
                message = "Choose a color",
                options = listOf("Red", "Blue"),
                min = 1,
                max = max,
                route = PromptRouteResolver.resolve(PromptSemantic.StaticColorChoice),
                sourceEntityId = source,
                staticList = StaticList.Colors,
                staticOptionIds = values,
            )

        fun awaitPublished(coordinator: MatchCutCoordinator): PublishedStaticChoiceInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.staticChoices.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.staticChoices.current()
            }
            return checkNotNull(published)
        }

        test("invalid response shapes leave the exact window and projection unchanged") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val finished = CountDownLatch(1)
            Thread {
                coordinator.staticChoiceRuntime(SeatId(1)).awaitSelection(request(board, max = 2), 3_000)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            val projection = board.bridge.projectionStateSnapshot()
            val counter = board.counter.snapshot()

            assertSoftly {
                coordinator.staticChoices.submit("${published.interactionId}-stale", published.gameStateId, listOf(values[0])) shouldBe
                    false
                coordinator.staticChoices.submit(published.interactionId, published.gameStateId + 1, listOf(values[0])) shouldBe false
                coordinator.staticChoices.submit(published.interactionId, published.gameStateId, emptyList()) shouldBe false
                coordinator.staticChoices.submit(published.interactionId, published.gameStateId, listOf(values[0], values[0])) shouldBe
                    false
                coordinator.staticChoices.submit(published.interactionId, published.gameStateId, listOf(Int.MAX_VALUE)) shouldBe false
                coordinator.staticChoices.current() shouldBe published
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.counter.snapshot() shouldBe counter
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                coordinator.staticChoices.submit(published.interactionId, published.gameStateId, listOf(values[1])) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                coordinator.staticChoices.submit(published.interactionId, published.gameStateId, listOf(values[1])) shouldBe false
            }
        }

        test("materialization failure retains the precise pre-install state") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            val failure =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.staticChoiceRuntime(SeatId(1)).awaitSelection(request(board, Int.MAX_VALUE), 3_000)
                }
            assertSoftly {
                failure.staticChoiceDiagnostic
                    .shouldNotBeNull()
                    .interaction.sourceForgeCardId
                    ?.value shouldBe Int.MAX_VALUE
                failure.pendingStaticChoiceCut.shouldBeNull()
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                board.bridge.projectionStateSnapshot() shouldBe prior
            }
        }

        test("enqueue failure preserves prior committed output") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val existing = listOf(GREToClientMessage.getDefaultInstance())
            coordinator.enqueueCommittedBatchForTest(SeatId(1), existing)
            coordinator.setBeforeBatchEnqueue(SeatId(1)) { _, _ -> error("static choice feed unavailable") }
            val failure =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.staticChoiceRuntime(SeatId(1)).awaitSelection(request(board), 3_000)
                }
            assertSoftly {
                failure.pendingStaticChoiceCut.shouldNotBeNull()
                coordinator.drain(SeatId(1)) shouldContainExactly listOf(existing)
            }
        }

        test("stale install removes owned output and retains the competing projection") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val competing =
                board.bridge
                    .projectionStateSnapshot()
                    .editor()
                    .freeze()
            coordinator.staticChoices.beforeInstall = { board.bridge.replaceProjectionStateForTest(competing) }
            val failure =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.staticChoiceRuntime(SeatId(1)).awaitSelection(request(board), 3_000)
                }

            assertSoftly {
                failure.pendingStaticChoiceCut.shouldNotBeNull()
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                board.bridge.projectionStateSnapshot() shouldBe competing
                coordinator.staticChoices.current().shouldBeNull()
            }
        }

        test("post-install acknowledgement failure retains committed output and projection") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            coordinator.staticChoices.afterInstall = { error("static choice acknowledgement unavailable") }
            val failure =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.staticChoiceRuntime(SeatId(1)).awaitSelection(request(board), 3_000)
                }
            val retained = coordinator.drain(SeatId(1)).single()
            assertSoftly {
                failure.pendingStaticChoiceCut.shouldNotBeNull().messages shouldBe retained
                retained.any { it.hasSelectNReq() } shouldBe true
                board.bridge.projectionStateSnapshot().revision shouldBe prior.revision + 1
                coordinator.staticChoices.current().shouldBeNull()
            }
        }

        test("response and timeout claims have one winner") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val timeoutEntered = CountDownLatch(1)
            val releaseTimeout = CountDownLatch(1)
            coordinator.staticChoices.beforeTimeoutClaim = {
                timeoutEntered.countDown()
                check(releaseTimeout.await(3, TimeUnit.SECONDS))
            }
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.staticChoiceRuntime(SeatId(1)).awaitSelection(request(board), 25))
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            timeoutEntered.await(3, TimeUnit.SECONDS) shouldBe true
            coordinator.staticChoices.submit(published.interactionId, published.gameStateId, listOf(values[1])) shouldBe true
            releaseTimeout.countDown()

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get() shouldContainExactly listOf(1)
                coordinator.failure().shouldBeNull()
            }
        }

        test("timeout retires the exact window and rejects a late response") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val failure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.staticChoiceRuntime(SeatId(1)).awaitSelection(request(board), 25) }
                    .onFailure(failure::set)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            finished.await(3, TimeUnit.SECONDS) shouldBe true

            assertSoftly {
                failure.get().shouldBeInstanceOf<StaticChoiceInteractionTimeoutException>()
                coordinator.staticChoices.submit(published.interactionId, published.gameStateId, listOf(values[0])) shouldBe false
                coordinator.staticChoices.current().shouldBeNull()
                coordinator.failure().shouldBeNull()
            }
        }

        test("production delivery failure retains the attempted cut and wakes the engine") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFailure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.staticChoiceRuntime(SeatId(1)).awaitSelection(request(board), null) }
                    .onFailure(engineFailure::set)
                finished.countDown()
            }.start()
            awaitPublished(coordinator)
            val cause = IllegalStateException("static choice delivery unavailable")
            val attempted = AtomicReference<List<GREToClientMessage>>()
            val sink =
                object : GreMessageSink {
                    override fun sendBundledGRE(messages: List<GREToClientMessage>) {
                        attempted.set(messages)
                        throw cause
                    }

                    override fun sendRealGameState(
                        bridge: GameBridge,
                        revealForSeat: Int?,
                    ) = Unit

                    override fun sendBundle(result: BundleBuilder.BundleResult) = Unit

                    override fun sendGameOver(reason: ResultReason) = Unit

                    override fun makeGRE(
                        type: GREMessageType,
                        gsId: Int,
                        msgId: Int,
                        configure: (GREToClientMessage.Builder) -> Unit,
                    ): GREToClientMessage = GREToClientMessage.getDefaultInstance()
                }
            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    drainOneCoordinatorBarrier(
                        sink,
                        synchronizationActionId = null,
                        drainCommitted = { coordinator.drain(SeatId(1)) },
                        completeSynchronization = { false },
                        awaitNext = {},
                        failDelivery = coordinator::failDelivery,
                    )
                }

            assertSoftly {
                terminal.cause shouldBe cause
                terminal.pendingStaticChoiceCut.shouldNotBeNull().messages shouldBe attempted.get()
                attempted.get().any { it.hasSelectNReq() } shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                engineFailure.get() shouldBe terminal
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                coordinator.staticChoices.current().shouldBeNull()
            }
        }

        test("delivery failure terminalizes before a concurrent response can claim the window") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFailure = AtomicReference<Throwable>()
            val engineFinished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.staticChoiceRuntime(SeatId(1)).awaitSelection(request(board), null) }
                    .onFailure(engineFailure::set)
                engineFinished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val committed = coordinator.drain(SeatId(1)).single()
            val choiceResultsBefore =
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .snapshotChoiceResults()
            val cutLocated = CountDownLatch(1)
            val releaseDelivery = CountDownLatch(1)
            coordinator.staticChoices.afterDeliveryCutLookup = {
                cutLocated.countDown()
                check(releaseDelivery.await(3, TimeUnit.SECONDS))
            }
            val cause = IllegalStateException("static choice delivery unavailable")
            val deliveryFailure = AtomicReference<Throwable>()
            val deliveryFinished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.failDelivery(cause) }.onFailure(deliveryFailure::set)
                deliveryFinished.countDown()
            }.start()
            cutLocated.await(3, TimeUnit.SECONDS) shouldBe true

            val responseStarted = CountDownLatch(1)
            val responseFailure = AtomicReference<Throwable>()
            val responseFinished = CountDownLatch(1)
            Thread {
                responseStarted.countDown()
                runCatching {
                    coordinator.staticChoices.submit(published.interactionId, published.gameStateId, listOf(values[0]))
                }.onFailure(responseFailure::set)
                responseFinished.countDown()
            }.start()
            responseStarted.await(3, TimeUnit.SECONDS) shouldBe true
            responseFinished.count shouldBe 1
            releaseDelivery.countDown()

            assertSoftly {
                deliveryFinished.await(3, TimeUnit.SECONDS) shouldBe true
                responseFinished.await(3, TimeUnit.SECONDS) shouldBe true
                engineFinished.await(3, TimeUnit.SECONDS) shouldBe true
                val terminal = deliveryFailure.get().shouldBeInstanceOf<PlaybackTerminalFailure>()
                terminal.cause shouldBe cause
                terminal.pendingStaticChoiceCut.shouldNotBeNull().messages shouldBe committed
                responseFailure.get() shouldBe terminal
                engineFailure.get() shouldBe terminal
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .snapshotChoiceResults() shouldBe choiceResultsBefore
                coordinator.staticChoices.current().shouldBeNull()
            }
            coordinator.staticChoices.afterDeliveryCutLookup = null
        }

        test("teardown wakes the exact waiter and clears the window") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFailure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.staticChoiceRuntime(SeatId(1)).awaitSelection(request(board), null) }
                    .onFailure(engineFailure::set)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            val cause = IllegalStateException("match closed")
            coordinator.shutdown(cause)

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                engineFailure.get().shouldBeInstanceOf<PlaybackTerminalFailure>().cause shouldBe cause
                coordinator.staticChoices.current().shouldBeNull()
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.staticChoices.submit(published.interactionId, published.gameStateId, listOf(values[0]))
                } shouldBe coordinator.failure()
            }
        }
    })
