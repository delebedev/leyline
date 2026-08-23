package leyline.bridge.coord

import forge.game.ability.AbilityKey
import forge.game.card.Card
import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.GatherCounterType
import leyline.bridge.handoff.GatherCountersSelection
import leyline.bridge.handoff.GatherCountersSourceValue
import leyline.bridge.handoff.GatherCountersWindowInput
import leyline.bridge.handoff.OneShotPayCostsWindow
import leyline.bridge.handoff.PayCostsPromptSourceInput
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** GatherCounters publication and terminal-race coverage. */
class GatherCountersRuntimeFailureTest :
    BoardTest({
        data class Fixture(
            val board: Board,
            val creatures: List<Card>,
            val window: GatherCountersWindowInput,
        )

        fun fixture(): Fixture {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Hopeful Initiate", human, ZoneType.Battlefield)
                    addCard("Hopeful Initiate", human, ZoneType.Battlefield)
                }
            board.bridge.cutCoordinator.registerViewer(SeatId(1))
            board.bridge.cutCoordinator.drain(SeatId(1))
            val creatures =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.isCreature }
            creatures.forEach {
                it.addCounterInternal(CounterEnumType.P1P1, 1, board.game.humanPlayer, true, null, AbilityKey.newMap())
            }
            val source = creatures.first()
            val ability = source.spellAbilities.first { it.isActivatedAbility() }
            val root = ability.rootAbility
            return Fixture(
                board,
                creatures,
                GatherCountersWindowInput(
                    PayCostsPromptSourceInput.StackAbility(
                        ability.id,
                        ForgeCardId(source.id),
                        root.definitionId,
                        root.targets
                            ?.targetCards
                            .orEmpty()
                            .map { ForgeCardId(it.id) },
                    ),
                    creatures.map { GatherCountersSourceValue(ForgeCardId(it.id), 1) },
                    2,
                    GatherCounterType.P1P1,
                ),
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

        test("capture failure retains the Gather terminal stage") {
            val capture = fixture()
            val missing = capture.window.sources + GatherCountersSourceValue(ForgeCardId(Int.MAX_VALUE), 1)
            val captureWindow = capture.window.copy(sources = missing)
            val captureFailure =
                shouldThrow<PlaybackTerminalFailure> {
                    capture.board.bridge.cutCoordinator.oneShotPayCosts.awaitGatherCounters(
                        captureWindow,
                        capture.creatures,
                        3_000,
                    )
                }
            assertSoftly {
                captureFailure.pendingPromptCut.shouldBeNull()
                capture.board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .shouldBeEmpty()
            }
        }

        test("enqueue failure retains the Gather terminal stage") {
            val enqueue = fixture()
            enqueue.board.bridge.cutCoordinator
                .setBeforeBatchEnqueue(SeatId(1)) { _, _ -> error("Gather feed unavailable") }
            val enqueueFailure =
                shouldThrow<PlaybackTerminalFailure> {
                    enqueue.board.bridge.cutCoordinator.oneShotPayCosts.awaitGatherCounters(
                        enqueue.window,
                        enqueue.creatures,
                        3_000,
                    )
                }
            assertSoftly {
                enqueueFailure.pendingPromptCut
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<OneShotPayCostsWindow>()
                enqueue.board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .shouldBeEmpty()
            }
        }

        test("delivery failure wins against Gather response") {
            val delivery = fixture()
            val coordinator = delivery.board.bridge.cutCoordinator
            val engineFailure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.oneShotPayCosts.awaitGatherCounters(delivery.window, delivery.creatures, null) }
                    .onFailure(engineFailure::set)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val committed = coordinator.drain(SeatId(1)).single()
            val sourceIds =
                committed
                    .single { it.hasPayCostsReq() }
                    .payCostsReq
                    .effectCostReq
                    .gatherReq
                    .sourcesList
                    .map { it.sourceId }
            val cutLocated = CountDownLatch(1)
            val releaseDelivery = CountDownLatch(1)
            coordinator.oneShotPayCosts.afterDeliveryCutLookup = {
                cutLocated.countDown()
                check(releaseDelivery.await(3, TimeUnit.SECONDS))
            }
            val terminalCause = IllegalStateException("Gather delivery unavailable")
            val terminalFailure = AtomicReference<Throwable>()
            val deliveryFinished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.failDelivery(terminalCause) }.onFailure(terminalFailure::set)
                deliveryFinished.countDown()
            }.start()
            cutLocated.await(3, TimeUnit.SECONDS) shouldBe true
            val responseFailure = AtomicReference<Throwable>()
            val responseFinished = CountDownLatch(1)
            Thread {
                runCatching {
                    coordinator.oneShotPayCosts.submitGatherCounters(
                        published.interactionId,
                        published.gameStateId,
                        sourceIds.map { GatherCountersSelection(it, 1) },
                    )
                }.onFailure(responseFailure::set)
                responseFinished.countDown()
            }.start()
            responseFinished.count shouldBe 1
            releaseDelivery.countDown()
            assertSoftly {
                deliveryFinished.await(3, TimeUnit.SECONDS) shouldBe true
                responseFinished.await(3, TimeUnit.SECONDS) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                val terminal = terminalFailure.get() as PlaybackTerminalFailure
                engineFailure.get() shouldBe terminal
                terminal.pendingPromptCut
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<OneShotPayCostsWindow>()
                terminal.pendingPromptCut.shouldNotBeNull().messages shouldBe committed
                responseFailure.get() shouldBe terminal
            }
            coordinator.oneShotPayCosts.afterDeliveryCutLookup = null
        }

        test("teardown wakes the pending Gather engine") {
            val teardown = fixture()
            val teardownFailure = AtomicReference<Throwable>()
            val teardownFinished = CountDownLatch(1)
            Thread {
                runCatching {
                    teardown.board.bridge.cutCoordinator.oneShotPayCosts.awaitGatherCounters(
                        teardown.window,
                        teardown.creatures,
                        null,
                    )
                }.onFailure(teardownFailure::set)
                teardownFinished.countDown()
            }.start()
            awaitPublished(teardown.board.bridge.cutCoordinator)
            val cause = IllegalStateException("match closed")
            teardown.board.bridge.cutCoordinator
                .shutdown(cause)
            assertSoftly {
                teardownFinished.await(3, TimeUnit.SECONDS) shouldBe true
                teardownFailure.get() shouldBe
                    teardown.board.bridge.cutCoordinator
                        .failure()
                teardown.board.bridge.cutCoordinator
                    .failure()
                    ?.cause shouldBe cause
                teardown.board.bridge.cutCoordinator.oneShotPayCosts
                    .current()
                    .shouldBeNull()
            }
        }
    })
