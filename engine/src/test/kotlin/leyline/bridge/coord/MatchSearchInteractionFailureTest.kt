package leyline.bridge.coord

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
import wotc.mtgo.gre.external.messaging.Messages.ResultReason
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchSearchInteractionFailureTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:search runtime failures
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Forest
            humanlibrary=Mountain;Forest
            ailibrary=Forest
            """.trimIndent()

        fun request(
            board: Board,
            entityIds: List<Int> =
                board.human
                    .getZone(ZoneType.Library)
                    .cards
                    .map { it.id },
        ): PromptRequest =
            PromptRequest(
                promptType = "choose_cards",
                message = "Search",
                options = entityIds.indices.map { "Candidate $it" },
                min = 1,
                max = 1,
                candidateRefs = entityIds.mapIndexed { index, id -> PromptCandidateRefDto(index, PromptCandidateKind.Card, id, "Library") },
                route = ResolvedPromptRoute.Search(PromptSemantic.Search),
            )

        fun awaitPublished(coordinator: MatchCutCoordinator): leyline.bridge.handoff.PublishedSearchInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.search.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.search.current()
            }
            return checkNotNull(published)
        }

        test("response wins a simultaneous timeout claim") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val timeoutEntered = CountDownLatch(1)
            val releaseTimeout = CountDownLatch(1)
            coordinator.search.beforeTimeoutClaim = {
                timeoutEntered.countDown()
                check(releaseTimeout.await(3, TimeUnit.SECONDS))
            }
            val result = AtomicReference<List<Int>>()
            val failure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.search.awaitSearch(request(board), 25) }
                    .onSuccess(result::set)
                    .onFailure(failure::set)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val selected =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSearchReq() }
                    .searchReq.itemsSoughtList
                    .first()
            timeoutEntered.await(3, TimeUnit.SECONDS) shouldBe true

            val accepted = AtomicReference<Boolean>()
            val response =
                Thread {
                    accepted.set(coordinator.search.submit(published.interactionId, published.gameStateId, listOf(selected)))
                }.also { it.start() }
            val responseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (accepted.get() == null && System.nanoTime() < responseDeadline) Thread.onSpinWait()
            releaseTimeout.countDown()
            response.join(3_000)

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                accepted.get() shouldBe true
                result.get() shouldContainExactly listOf(0)
                failure.get().shouldBeNull()
                coordinator.failure().shouldBeNull()
            }
            coordinator.search.beforeTimeoutClaim = null
        }

        test("materialization failure retains frozen input without projection or output") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()

            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.search.awaitSearch(request(board, listOf(Int.MAX_VALUE)), 3_000)
                }
            assertSoftly {
                terminal.searchDiagnostic
                    .shouldNotBeNull()
                    .interaction.candidateCardIdsByOption.values
                    .single()
                    .value shouldBe
                    Int.MAX_VALUE
                terminal.pendingSearchCut.shouldBeNull()
                coordinator.drain(SeatId(1)) shouldBe emptyList()
                board.bridge.projectionStateSnapshot() shouldBe prior
                coordinator.search
                    .current()
                    .shouldBeNull()
            }
        }

        test("enqueue failure leaves a preexisting batch and retains the exact cut") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            val existing = listOf(GREToClientMessage.getDefaultInstance())
            coordinator.enqueueCommittedBatchForTest(SeatId(1), existing)
            coordinator.setBeforeBatchEnqueue(SeatId(1)) { _, _ -> error("search feed unavailable") }

            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.search.awaitSearch(request(board), 3_000)
                }
            assertSoftly {
                terminal.cause?.message shouldBe "search feed unavailable"
                terminal.pendingSearchCut.shouldNotBeNull()
                coordinator.drain(SeatId(1)) shouldContainExactly listOf(existing)
                board.bridge.projectionStateSnapshot() shouldBe prior
            }
            coordinator.setBeforeBatchEnqueue(SeatId(1), null)
        }

        test("stale installation removes owned output and keeps the competing projection") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val competing =
                board.bridge
                    .projectionStateSnapshot()
                    .editor()
                    .freeze()
            coordinator.search.beforeInstall = { board.bridge.replaceProjectionStateForTest(competing) }

            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.search.awaitSearch(request(board), 3_000)
                }
            assertSoftly {
                terminal.pendingSearchCut.shouldNotBeNull()
                coordinator.drain(SeatId(1)) shouldBe emptyList()
                board.bridge.projectionStateSnapshot() shouldBe competing
            }
            coordinator.search.beforeInstall = null
        }

        test("post-install acknowledgement failure retains committed state and output") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            coordinator.search.afterInstall = { error("search acknowledgement unavailable") }

            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.search.awaitSearch(request(board), 3_000)
                }
            val retained = coordinator.drain(SeatId(1)).single()
            assertSoftly {
                terminal.pendingSearchCut.shouldNotBeNull().messages shouldBe retained
                retained.any { it.hasSearchReq() } shouldBe true
                board.bridge.projectionStateSnapshot().revision shouldBe prior.revision + 1
                coordinator.search
                    .current()
                    .shouldBeNull()
            }
            coordinator.search.afterInstall = null
        }

        test("delivery failure wakes the waiter and retains committed output") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val failure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.search.awaitSearch(request(board), null) }
                    .onFailure(failure::set)
                finished.countDown()
            }.start()
            awaitPublished(coordinator)
            val cause = IllegalStateException("search delivery unavailable")
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
                        sink = sink,
                        synchronizationActionId = null,
                        drainCommitted = { coordinator.drain(SeatId(1)) },
                        completeSynchronization = { false },
                        awaitNext = {},
                        failDelivery = coordinator::failDelivery,
                    )
                }

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                failure.get() shouldBe terminal
                terminal.cause shouldBe cause
                attempted.get().any { it.hasSearchReq() } shouldBe true
                terminal.pendingSearchCut.shouldNotBeNull().messages shouldBe attempted.get()
                coordinator.drain(SeatId(1)) shouldBe emptyList()
                coordinator.search
                    .current()
                    .shouldBeNull()
            }
        }

        test("delivery failure terminalizes before a concurrent response can claim the window") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFailure = AtomicReference<Throwable>()
            val engineFinished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.search.awaitSearch(request(board), null) }
                    .onFailure(engineFailure::set)
                engineFinished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val committed = coordinator.drain(SeatId(1)).single()
            val selected =
                committed
                    .single { it.hasSearchReq() }
                    .searchReq.itemsSoughtList
                    .first()
            val cutLocated = CountDownLatch(1)
            val releaseDelivery = CountDownLatch(1)
            coordinator.search.afterDeliveryCutLookup = {
                cutLocated.countDown()
                check(releaseDelivery.await(3, TimeUnit.SECONDS))
            }
            val deliveryCause = IllegalStateException("search delivery unavailable")
            val deliveryFailure = AtomicReference<Throwable>()
            val deliveryFinished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.failDelivery(deliveryCause) }.onFailure(deliveryFailure::set)
                deliveryFinished.countDown()
            }.start()
            cutLocated.await(3, TimeUnit.SECONDS) shouldBe true

            val responseStarted = CountDownLatch(1)
            val responseFailure = AtomicReference<Throwable>()
            val responseFinished = CountDownLatch(1)
            Thread {
                responseStarted.countDown()
                runCatching {
                    coordinator.search.submit(published.interactionId, published.gameStateId, listOf(selected))
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
                val terminal = deliveryFailure.get() as PlaybackTerminalFailure
                terminal.cause shouldBe deliveryCause
                terminal.pendingSearchCut.shouldNotBeNull().messages shouldBe committed
                responseFailure.get() shouldBe terminal
                engineFailure.get() shouldBe terminal
                coordinator.search
                    .current()
                    .shouldBeNull()
            }
            coordinator.search.afterDeliveryCutLookup = null
        }
    })
