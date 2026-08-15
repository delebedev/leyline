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
import leyline.bridge.handoff.GroupingInteractionResult
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.game.bundle.BundleBuilder
import leyline.game.state.GameBridge
import leyline.match.GreMessageSink
import leyline.match.drainOneCoordinatorBarrier
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import wotc.mtgo.gre.external.messaging.Messages.ResultReason
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchGroupingInteractionFailureTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:grouping failures
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanlibrary=Mountain;Forest
            ailibrary=Forest
            """.trimIndent()

        fun options(board: Board): List<Card> =
            board.human
                .getZone(ZoneType.Library)
                .cards
                .toList()
                .take(2)

        fun request(board: Board): PromptRequest {
            val cards = options(board)
            return PromptRequest(
                promptType = "choose_cards",
                message = "Arrange cards",
                options = cards.map { it.name },
                min = 0,
                max = cards.size,
                candidateRefs =
                    cards.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Library.name)
                    },
                route = ResolvedPromptRoute.Grouping(PromptSemantic.GroupingScry, GroupingContext.Scry_a0f6),
            )
        }

        fun singleRequest(board: Board): PromptRequest {
            val card = options(board).first()
            return request(board).copy(
                promptType = "confirm",
                options = listOf("Top of library", "Bottom of library"),
                min = 1,
                max = 1,
                candidateRefs = listOf(PromptCandidateRefDto(0, PromptCandidateKind.Card, card.id, ZoneType.Library.name)),
            )
        }

        fun awaitPublished(coordinator: MatchCutCoordinator): leyline.bridge.handoff.PublishedGroupingInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var current = coordinator.grouping.current()
            while (current == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                current = coordinator.grouping.current()
            }
            return checkNotNull(current)
        }

        test("materialization failure retains frozen input without projection or output") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            coordinator.grouping.beforeMaterialize = { error("grouping materialization unavailable") }

            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.grouping.awaitGrouping(request(board), options(board), 3_000)
                }
            assertSoftly {
                terminal.cause?.message shouldBe "grouping materialization unavailable"
                terminal.groupingDiagnostic
                    .shouldNotBeNull()
                    .interaction.candidates.size shouldBe 2
                terminal.pendingGroupingCut.shouldBeNull()
                coordinator.drain(SeatId(1)) shouldBe emptyList()
                board.bridge.projectionStateSnapshot() shouldBe prior
            }
        }

        test("enqueue failure retains the exact cut without installing projection") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            val existing = listOf(GREToClientMessage.getDefaultInstance())
            coordinator.enqueueCommittedBatchForTest(SeatId(1), existing)
            coordinator.setBeforeBatchEnqueue(SeatId(1)) { _, _ -> error("grouping feed unavailable") }

            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.grouping.awaitGrouping(request(board), options(board), 3_000)
                }
            assertSoftly {
                terminal.pendingGroupingCut.shouldNotBeNull()
                coordinator.drain(SeatId(1)) shouldContainExactly listOf(existing)
                board.bridge.projectionStateSnapshot() shouldBe prior
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
            coordinator.grouping.beforeInstall = { board.bridge.replaceProjectionStateForTest(competing) }

            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.grouping.awaitGrouping(request(board), options(board), 3_000)
                }
            assertSoftly {
                terminal.pendingGroupingCut.shouldNotBeNull()
                coordinator.drain(SeatId(1)) shouldBe emptyList()
                board.bridge.projectionStateSnapshot() shouldBe competing
            }
        }

        test("post-install acknowledgement failure retains committed state and output") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            coordinator.grouping.afterInstall = { error("grouping acknowledgement unavailable") }

            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.grouping.awaitGrouping(request(board), options(board), 3_000)
                }
            val retained = coordinator.drain(SeatId(1)).single()
            assertSoftly {
                terminal.pendingGroupingCut.shouldNotBeNull().messages shouldBe retained
                retained.any { it.hasGroupReq() } shouldBe true
                board.bridge.projectionStateSnapshot().revision shouldBe prior.revision + 1
                coordinator.grouping
                    .current()
                    .shouldBeNull()
            }
        }

        test("response wins an overlapping timeout claim") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val timeoutEntered = CountDownLatch(1)
            val releaseTimeout = CountDownLatch(1)
            coordinator.grouping.beforeTimeoutClaim = {
                timeoutEntered.countDown()
                check(releaseTimeout.await(3, TimeUnit.SECONDS))
            }
            val result = AtomicReference<GroupingInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.grouping.awaitGrouping(request(board), options(board), 25))
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val ids =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasGroupReq() }
                    .groupReq.instanceIdsList
            timeoutEntered.await(3, TimeUnit.SECONDS) shouldBe true
            coordinator.grouping.submit(published.interactionId, published.gameStateId, ids.reversed(), emptyList()) shouldBe true
            releaseTimeout.countDown()

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().timedOut shouldBe false
                result.get().topHandles.map { it.id } shouldContainExactly options(board).reversed().map { it.id }
                coordinator.failure().shouldBeNull()
            }
            coordinator.grouping.finalizeArrangement(result.get(), result.get().topHandles, emptyList())
        }

        test("timeout winner returns the legacy default partition and rejects a late response") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<GroupingInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.grouping.awaitGrouping(request(board), options(board), 25))
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val ids =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasGroupReq() }
                    .groupReq.instanceIdsList
            finished.await(3, TimeUnit.SECONDS) shouldBe true
            val projection = board.bridge.projectionStateSnapshot()
            val counter = board.counter.snapshot()

            assertSoftly {
                result.get().timedOut shouldBe true
                result.get().awayHandles.single() shouldBe options(board).first()
                coordinator.grouping.submit(published.interactionId, published.gameStateId, ids, emptyList()) shouldBe false
                coordinator.grouping
                    .current()
                    .shouldBeNull()
                coordinator.failure().shouldBeNull()
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.counter.snapshot() shouldBe counter
            }
            coordinator.grouping.finalizeArrangement(result.get(), result.get().topHandles, result.get().awayHandles)
        }

        test("single-card timeout keeps the default card on top and rejects a late response") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val card = options(board).first()
            val result = AtomicReference<GroupingInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.grouping.awaitGrouping(singleRequest(board), listOf(card), 25))
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val id =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasGroupReq() }
                    .groupReq.instanceIdsList
                    .single()
            finished.await(3, TimeUnit.SECONDS) shouldBe true

            assertSoftly {
                result.get().timedOut shouldBe true
                result.get().topHandles.single() shouldBe card
                result.get().awayHandles shouldBe emptyList()
                coordinator.grouping.submit(published.interactionId, published.gameStateId, listOf(id), emptyList()) shouldBe false
                coordinator.grouping
                    .current()
                    .shouldBeNull()
                coordinator.failure().shouldBeNull()
            }
            coordinator.grouping.finalizeArrangement(result.get(), result.get().topHandles, emptyList())
        }

        test("delivery failure wakes the engine and retains the attempted exact cut") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFailure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.grouping.awaitGrouping(request(board), options(board), null) }
                    .onFailure(engineFailure::set)
                finished.countDown()
            }.start()
            awaitPublished(coordinator)
            val cause = IllegalStateException("grouping delivery unavailable")
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
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                engineFailure.get() shouldBe terminal
                terminal.cause shouldBe cause
                terminal.pendingGroupingCut.shouldNotBeNull().messages shouldBe attempted.get()
                coordinator.drain(SeatId(1)) shouldBe emptyList()
            }
        }

        test("delivery failure terminalizes before a concurrent response can claim the window") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFailure = AtomicReference<Throwable>()
            val engineFinished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.grouping.awaitGrouping(request(board), options(board), null) }
                    .onFailure(engineFailure::set)
                engineFinished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val committed = coordinator.drain(SeatId(1)).single()
            val ids = committed.single { it.hasGroupReq() }.groupReq.instanceIdsList
            val cutLocated = CountDownLatch(1)
            val releaseDelivery = CountDownLatch(1)
            coordinator.grouping.afterDeliveryCutLookup = {
                cutLocated.countDown()
                check(releaseDelivery.await(3, TimeUnit.SECONDS))
            }
            val cause = IllegalStateException("grouping delivery unavailable")
            val deliveryFailure = AtomicReference<Throwable>()
            val deliveryFinished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.failDelivery(cause) }.onFailure(deliveryFailure::set)
                deliveryFinished.countDown()
            }.start()
            cutLocated.await(3, TimeUnit.SECONDS) shouldBe true

            val responseFailure = AtomicReference<Throwable>()
            val responseStarted = CountDownLatch(1)
            val responseFinished = CountDownLatch(1)
            Thread {
                responseStarted.countDown()
                runCatching { coordinator.grouping.submit(published.interactionId, published.gameStateId, ids, emptyList()) }
                    .onFailure(responseFailure::set)
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
                terminal.pendingGroupingCut.shouldNotBeNull().messages shouldBe committed
                responseFailure.get() shouldBe terminal
                engineFailure.get() shouldBe terminal
            }
        }

        test("shutdown wakes the exact Grouping waiter and clears live state") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFailure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.grouping.awaitGrouping(request(board), options(board), null) }
                    .onFailure(engineFailure::set)
                finished.countDown()
            }.start()
            awaitPublished(coordinator)
            val cause = IllegalStateException("grouping shutdown")
            coordinator.shutdown(cause)

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                engineFailure.get() shouldBe coordinator.failure()
                coordinator.failure().shouldNotBeNull().cause shouldBe cause
                coordinator.grouping
                    .current()
                    .shouldBeNull()
            }
        }

        test("shutdown clears a completed result awaiting arrangement finalization") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<GroupingInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.grouping.awaitGrouping(request(board), options(board), null))
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val ids =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasGroupReq() }
                    .groupReq.instanceIdsList
            coordinator.grouping.submit(published.interactionId, published.gameStateId, ids, emptyList()) shouldBe true
            finished.await(3, TimeUnit.SECONDS) shouldBe true

            val promptBridge = board.bridge.promptBridge(SeatId(1))
            board.bridge.teardownResources()
            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    promptBridge.finalizeGroupingArrangement(result.get(), result.get().topHandles, emptyList())
                }

            assertSoftly {
                terminal shouldBe coordinator.failure()
                coordinator.grouping
                    .pollArrangement(SeatId(1), GroupingContext.Scry_a0f6)
                    .shouldBeNull()
            }
        }
    })
