package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.PublishedRevealChoiceInteraction
import leyline.bridge.handoff.RevealChoiceWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.game.state.GameBridge
import leyline.match.GreMessageSink
import leyline.match.drainOneCoordinatorBarrier
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ResultReason
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchRevealChoiceInteractionFailureTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:reveal choice failures
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Island
            humanlibrary=Forest
            aihand=Mountain;Forest
            ailibrary=Grizzly Bears
            """.trimIndent()

        fun options(board: Board): List<Card> =
            board.ai
                .getZone(ZoneType.Hand)
                .cards
                .toList()

        fun entry(board: Board): leyline.bridge.handoff.PromptJournal.RevealEntry {
            board.bridge.promptBridge(SeatId(1)).journal.record(
                PromptSideEffect.RevealStarted(options(board).map { ForgeCardId(it.id) }, SeatId(2)),
            )
            return checkNotNull(
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .activeRevealEntry(),
            )
        }

        fun request(
            board: Board,
            sourceId: Int? =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
                    .id,
            max: Int = 1,
        ): PromptRequest =
            PromptRequest(
                promptType = "choose_cards",
                message = "Choose a card",
                options = options(board).map { it.name },
                min = 1,
                max = max,
                defaultIndex = 0,
                candidateRefs =
                    options(board).mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Hand.name)
                    },
                route = PromptRouteResolver.resolve(PromptSemantic.RevealChoose),
                sourceEntityId = sourceId,
            )

        fun awaitPublished(coordinator: MatchCutCoordinator): PublishedRevealChoiceInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.revealChoices.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.revealChoices.current()
            }
            return checkNotNull(published)
        }

        test("invalid response shapes leave the exact reveal window and projection unchanged") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val entry = entry(board)
            val finished = CountDownLatch(1)
            Thread {
                coordinator.revealChoices.awaitSelection(request(board, max = 2), options(board), entry, false, 3_000)
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
                coordinator.revealChoices.submit("${published.interactionId}-stale", published.gameStateId, listOf(ids[0])) shouldBe
                    false
                coordinator.revealChoices.submit(published.interactionId, published.gameStateId + 1, listOf(ids[0])) shouldBe false
                coordinator.revealChoices.submit(published.interactionId, published.gameStateId, emptyList()) shouldBe false
                coordinator.revealChoices.submit(published.interactionId, published.gameStateId, listOf(ids[0], ids[0])) shouldBe
                    false
                coordinator.revealChoices.submit(published.interactionId, published.gameStateId, listOf(Int.MAX_VALUE)) shouldBe
                    false
                coordinator.revealChoices.current() shouldBe published
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.counter.snapshot() shouldBe counter
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                coordinator.revealChoices.submit(published.interactionId, published.gameStateId, listOf(ids[1])) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                coordinator.revealChoices.submit(published.interactionId, published.gameStateId, listOf(ids[1])) shouldBe false
            }
        }

        test("delivery failure retains the attempted cut and wakes the engine") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFailure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching {
                    coordinator.revealChoices.awaitSelection(
                        request(board),
                        options(board),
                        entry(board),
                        true,
                        null,
                    )
                }.onFailure(engineFailure::set)
                finished.countDown()
            }.start()
            awaitPublished(coordinator)
            val journal = board.bridge.promptBridge(SeatId(1)).journal
            journal.record(PromptSideEffect.RevealStarted(listOf(ForgeCardId(options(board)[1].id)), SeatId(2)))
            val replacement = checkNotNull(journal.activeRevealEntry())
            val cause = IllegalStateException("reveal delivery unavailable")
            val attempted = AtomicReference<List<GREToClientMessage>>()
            val sink = throwingSink(cause, attempted)
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
                terminal.pendingPromptCut
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<RevealChoiceWindowValue>()
                terminal.pendingPromptCut.shouldNotBeNull().messages shouldBe attempted.get()
                attempted.get().any { it.hasSelectNReq() } shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                engineFailure.get() shouldBe terminal
                journal.activeRevealEntry() shouldBe replacement
                journal.consumeExiledUnderSource(ForgeCardId(options(board)[0].id)).shouldBeNull()
                coordinator.revealChoices
                    .current()
                    .shouldBeNull()
            }
        }

        test("delivery terminalizes before a concurrent response can claim the window") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFailure = AtomicReference<Throwable>()
            val engineFinished = CountDownLatch(1)
            Thread {
                runCatching {
                    coordinator.revealChoices.awaitSelection(
                        request(board),
                        options(board),
                        entry(board),
                        true,
                        null,
                    )
                }.onFailure(engineFailure::set)
                engineFinished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val committed = coordinator.drain(SeatId(1)).single()
            val id =
                committed
                    .single { it.hasSelectNReq() }
                    .selectNReq.idsList
                    .first()
            val cutLocated = CountDownLatch(1)
            val releaseDelivery = CountDownLatch(1)
            coordinator.prompts.settled.afterDeliveryCutLookup = {
                cutLocated.countDown()
                check(releaseDelivery.await(3, TimeUnit.SECONDS))
            }
            val cause = IllegalStateException("reveal delivery unavailable")
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
                runCatching { coordinator.revealChoices.submit(published.interactionId, published.gameStateId, listOf(id)) }
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
                terminal.pendingPromptCut
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<RevealChoiceWindowValue>()
                terminal.pendingPromptCut.shouldNotBeNull().messages shouldBe committed
                responseFailure.get() shouldBe terminal
                engineFailure.get() shouldBe terminal
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .consumeExiledUnderSource(ForgeCardId(options(board)[0].id))
                    .shouldBeNull()
                coordinator.revealChoices
                    .current()
                    .shouldBeNull()
            }
            coordinator.prompts.settled.afterDeliveryCutLookup = null
        }

        test("teardown wakes the exact waiter and clears retained handles") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFailure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching {
                    coordinator.revealChoices.awaitSelection(
                        request(board),
                        options(board),
                        entry(board),
                        false,
                        null,
                    )
                }.onFailure(engineFailure::set)
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
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .activeRevealEntry()
                    .shouldBeNull()
                coordinator.revealChoices
                    .current()
                    .shouldBeNull()
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.revealChoices.submit(published.interactionId, published.gameStateId, listOf(id))
                } shouldBe coordinator.failure()
            }
        }
    }) {
    companion object {
        private fun throwingSink(
            cause: Exception,
            attempted: AtomicReference<List<GREToClientMessage>>,
        ): GreMessageSink =
            object : GreMessageSink {
                override fun sendBundledGRE(messages: List<GREToClientMessage>) {
                    attempted.set(messages)
                    throw cause
                }

                override fun sendRealGameState(
                    bridge: GameBridge,
                    revealForSeat: Int?,
                ) = Unit

                override fun sendGameOver(reason: ResultReason) = Unit
            }
    }
}
