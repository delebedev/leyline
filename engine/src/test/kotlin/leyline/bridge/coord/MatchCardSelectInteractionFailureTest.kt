package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.CardSelectInteractionResult
import leyline.bridge.handoff.CardSelectInteractionTimeoutException
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
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

class MatchCardSelectInteractionFailureTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:card select failures
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Mountain;Forest
            humanbattlefield=Island
            ailibrary=Forest
            """.trimIndent()

        fun options(board: Board): List<Card> =
            board.human
                .getZone(ZoneType.Hand)
                .cards
                .toList()

        fun request(
            board: Board,
            sourceId: Int? =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
                    .id,
        ): PromptRequest {
            val cards = options(board)
            return PromptRequest(
                promptType = "choose_cards",
                message = "Choose a permanent",
                options = cards.map { it.name },
                min = 1,
                max = 1,
                defaultIndex = 0,
                candidateRefs =
                    cards.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Hand.name)
                    },
                route = PromptRouteResolver.resolve(PromptSemantic.SelectNSacrificeEffect),
                sourceEntityId = sourceId,
            )
        }

        fun awaitPublished(coordinator: MatchCutCoordinator): leyline.bridge.handoff.PublishedCardSelectInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.cardSelect.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.cardSelect.current()
            }
            return checkNotNull(published)
        }

        test("invalid response shapes leave the exact window and projection unchanged") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val finished = CountDownLatch(1)
            Thread {
                coordinator.cardSelectRuntime(SeatId(1)).awaitSelection(request(board), options(board), 3_000)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val ids =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSelectNReq() }
                    .selectNReq.idsList
            val projection = board.bridge.projectionStateSnapshot()
            val counter = board.counter.snapshot()

            assertSoftly {
                coordinator.cardSelect.submitSelectN(
                    published.interactionId,
                    published.gameStateId + 1,
                    listOf(ids[0]),
                ) shouldBe
                    false
                coordinator.cardSelect.submitSelectN(
                    published.interactionId,
                    published.gameStateId,
                    emptyList(),
                ) shouldBe
                    false
                coordinator.cardSelect.submitSelectN(
                    published.interactionId,
                    published.gameStateId,
                    listOf(ids[0], ids[0]),
                ) shouldBe
                    false
                coordinator.cardSelect.submitSelectN(
                    published.interactionId,
                    published.gameStateId,
                    listOf(Int.MAX_VALUE),
                ) shouldBe
                    false
                coordinator.cardSelect.current() shouldBe published
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.counter.snapshot() shouldBe counter
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                coordinator.cardSelect.submitSelectN(
                    published.interactionId,
                    published.gameStateId,
                    listOf(ids[1]),
                ) shouldBe
                    true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                coordinator.cardSelect.submitSelectN(
                    published.interactionId,
                    published.gameStateId,
                    listOf(ids[1]),
                ) shouldBe
                    false
            }
        }

        test("materialization failure retains the precise pre-install state") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            val failure =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.cardSelectRuntime(SeatId(1)).awaitSelection(
                        request(board, Int.MAX_VALUE),
                        options(board),
                        3_000,
                    )
                }
            assertSoftly {
                failure.cardSelectDiagnostic
                    .shouldNotBeNull()
                    .interaction.sourceForgeCardId
                    ?.value shouldBe Int.MAX_VALUE
                failure.pendingCardSelectCut.shouldBeNull()
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                board.bridge.projectionStateSnapshot() shouldBe prior
            }
        }

        test("enqueue failure retains previously committed output") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val existing = listOf(GREToClientMessage.getDefaultInstance())
            coordinator.enqueueCommittedBatchForTest(SeatId(1), existing)
            coordinator.setBeforeBatchEnqueue(SeatId(1)) { _, _ -> error("card select feed unavailable") }
            val failure =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.cardSelectRuntime(SeatId(1)).awaitSelection(
                        request(board),
                        options(board),
                        3_000,
                    )
                }
            assertSoftly {
                failure.pendingCardSelectCut.shouldNotBeNull()
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
            coordinator.cardSelect.beforeInstall = { board.bridge.replaceProjectionStateForTest(competing) }
            val failure =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.cardSelectRuntime(SeatId(1)).awaitSelection(request(board), options(board), 3_000)
                }
            assertSoftly {
                failure.pendingCardSelectCut.shouldNotBeNull()
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                board.bridge.projectionStateSnapshot() shouldBe competing
            }
        }

        test("post-install acknowledgement failure retains committed output and projection") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val prior = board.bridge.projectionStateSnapshot()
            coordinator.cardSelect.afterInstall = { error("card select acknowledgement unavailable") }
            val failure =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.cardSelectRuntime(SeatId(1)).awaitSelection(request(board), options(board), 3_000)
                }
            val retained = coordinator.drain(SeatId(1)).single()
            assertSoftly {
                failure.pendingCardSelectCut.shouldNotBeNull().messages shouldBe retained
                retained.any { it.hasSelectNReq() } shouldBe true
                board.bridge.projectionStateSnapshot().revision shouldBe prior.revision + 1
                coordinator.cardSelect.current().shouldBeNull()
            }
        }

        test("response and timeout claims have one winner") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val timeoutEntered = CountDownLatch(1)
            val releaseTimeout = CountDownLatch(1)
            coordinator.cardSelect.beforeTimeoutClaim = {
                timeoutEntered.countDown()
                check(releaseTimeout.await(3, TimeUnit.SECONDS))
            }
            val result = AtomicReference<CardSelectInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.cardSelectRuntime(SeatId(1)).awaitSelection(request(board), options(board), 25))
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val id =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSelectNReq() }
                    .selectNReq.idsList[1]
            timeoutEntered.await(3, TimeUnit.SECONDS) shouldBe true
            coordinator.cardSelect.submitSelectN(
                published.interactionId,
                published.gameStateId,
                listOf(id),
            ) shouldBe
                true
            releaseTimeout.countDown()

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().optionIndices shouldContainExactly listOf(1)
                coordinator.failure().shouldBeNull()
            }
        }

        test("timeout winner retires the exact window and rejects a late response") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val failure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.cardSelectRuntime(SeatId(1)).awaitSelection(request(board), options(board), 25) }
                    .onFailure(failure::set)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val id =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSelectNReq() }
                    .selectNReq.idsList
                    .first()
            finished.await(3, TimeUnit.SECONDS) shouldBe true

            assertSoftly {
                failure.get().shouldBeInstanceOf<CardSelectInteractionTimeoutException>()
                coordinator.cardSelect.submitSelectN(
                    published.interactionId,
                    published.gameStateId,
                    listOf(id),
                ) shouldBe
                    false
                coordinator.cardSelect.current().shouldBeNull()
                coordinator.failure().shouldBeNull()
            }
        }

        test("delivery failure retains the attempted cut and wakes the engine") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFailure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.cardSelectRuntime(SeatId(1)).awaitSelection(request(board), options(board), null) }
                    .onFailure(engineFailure::set)
                finished.countDown()
            }.start()
            awaitPublished(coordinator)
            val attempted = coordinator.drain(SeatId(1)).single()
            val cause = IllegalStateException("card select delivery unavailable")
            val terminal = shouldThrow<PlaybackTerminalFailure> { coordinator.failDelivery(cause) }

            assertSoftly {
                terminal.cause shouldBe cause
                terminal.pendingCardSelectCut.shouldNotBeNull().messages shouldBe attempted
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                engineFailure.get() shouldBe terminal
                coordinator.cardSelect.current().shouldBeNull()
            }
        }

        test("teardown wakes the exact waiter and clears retained handles") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFailure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.cardSelectRuntime(SeatId(1)).awaitSelection(request(board), options(board), null) }
                    .onFailure(engineFailure::set)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val id =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSelectNReq() }
                    .selectNReq.idsList
                    .first()
            val cause = IllegalStateException("match closed")
            coordinator.shutdown(cause)

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                engineFailure.get().shouldBeInstanceOf<PlaybackTerminalFailure>().cause shouldBe cause
                coordinator.cardSelect.current().shouldBeNull()
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.cardSelect.submitSelectN(
                        published.interactionId,
                        published.gameStateId,
                        listOf(id),
                    )
                } shouldBe coordinator.failure()
            }
        }
    })
