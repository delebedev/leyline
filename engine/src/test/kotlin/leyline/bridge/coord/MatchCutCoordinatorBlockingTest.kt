package leyline.bridge.coord

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.CommanderReturnPromptContext
import leyline.bridge.handoff.CommanderZone
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.testkit.BoardTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchCutCoordinatorBlockingTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:coordinator blocking interactions
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Forest
            humanbattlefield=Forest
            humanlibrary=Forest
            ailibrary=Forest
            """.trimIndent()

        test("stale commander interaction retains exact cut without installing identity or limbo") {
            val board = startPuzzleAtMain1(puzzle)
            board.bridge.prioritySignal.awaitSignal(0)
            board.bridge.cutCoordinator.drain(SeatId(1))
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first()
            val sourceId = ForgeCardId(source.id)
            val oldInstanceId = board.bridge.getOrAllocInstanceId(sourceId).value
            val promptInstanceId = board.bridge.reserveInstanceId().value
            val interaction =
                BlockingInteraction.Optional(
                    sourceId = sourceId,
                    forceSnapshotBeforePrompt = true,
                    customPromptId = null,
                    commanderReturn =
                        CommanderReturnPromptContext(
                            oldInstanceId = oldInstanceId,
                            promptInstanceId = promptInstanceId,
                            originZone = CommanderZone.Battlefield,
                            destinationZone = CommanderZone.Graveyard,
                            ownerSeatId = 1,
                            transferCategory = "Destroy",
                        ),
                )
            val prior = board.bridge.projectionStateSnapshot()
            val competing = prior.editor().freeze()
            board.bridge.cutCoordinator.beforeBlockingInstall = {
                board.bridge.replaceProjectionStateForTest(competing)
            }
            val thrown = AtomicReference<Throwable>()

            val thread =
                Thread {
                    try {
                        board.bridge.cutCoordinator.awaitOptional(interaction, timeoutMs = 1_000, defaultOnTimeout = false)
                    } catch (failure: Throwable) {
                        thrown.set(failure)
                    }
                }
            thread.start()
            thread.join(3_000)
            board.bridge.cutCoordinator.beforeBlockingInstall = null

            val terminal = thrown.get() as? PlaybackTerminalFailure
            assertSoftly {
                terminal.shouldNotBeNull()
                terminal.pendingPromptCut.shouldNotBeNull().interaction shouldBe interaction
                board.bridge.projectionStateSnapshot() shouldBe competing
                board.bridge.projectionStateSnapshot().limboInstanceIds shouldNotContain promptInstanceId
                board.bridge.cutCoordinator.currentBlockingInteraction() shouldBe null
                board.bridge.cutCoordinator.drain(SeatId(1)) shouldBe emptyList()
            }
        }

        test("commander cleanup materialization failure terminalizes waiter without regressing ids") {
            val board = startPuzzleAtMain1(puzzle)
            board.bridge.cutCoordinator.drain(SeatId(1))
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first()
            val sourceId = ForgeCardId(source.id)
            val promptInstanceId = board.bridge.reserveInstanceId().value
            val interaction =
                BlockingInteraction.Optional(
                    sourceId,
                    true,
                    null,
                    CommanderReturnPromptContext(
                        board.bridge.getOrAllocInstanceId(sourceId).value,
                        promptInstanceId,
                        CommanderZone.Battlefield,
                        CommanderZone.Graveyard,
                        1,
                        "Destroy",
                    ),
                )
            val failure = AtomicReference<Throwable>()
            val engine =
                Thread {
                    try {
                        board.bridge.cutCoordinator.awaitOptional(interaction, 3_000, false)
                    } catch (ex: Throwable) {
                        failure.set(ex)
                    }
                }.also { it.start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var pending = board.bridge.cutCoordinator.currentBlockingInteraction()
            while (pending == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                pending = board.bridge.cutCoordinator.currentBlockingInteraction()
            }
            val exact = checkNotNull(pending)
            val projection = board.bridge.projectionStateSnapshot()
            val counter = board.counter.snapshot()
            board.bridge.cutCoordinator.beforeCommanderCleanupMaterialization = { error("cleanup materialization failed") }

            shouldThrow<PlaybackTerminalFailure> {
                board.bridge.cutCoordinator.submitOptionalAnswer(exact.interactionId, exact.gameStateId, true)
            }.cause?.message shouldBe "cleanup materialization failed"
            engine.join(3_000)
            board.bridge.cutCoordinator.beforeCommanderCleanupMaterialization = null

            assertSoftly {
                failure.get().shouldBeInstanceOf<PlaybackTerminalFailure>()
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.counter.currentMsgId() shouldBe counter.currentMsgId
                board.bridge.cutCoordinator.currentBlockingInteraction() shouldBe null
            }
        }

        test("initial interaction materialization failure leaves an unpublished id gap and emits nothing") {
            val board = startPuzzleAtMain1(puzzle)
            board.bridge.cutCoordinator.drain(SeatId(1))
            val sourceId =
                ForgeCardId(
                    board.human
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .first()
                        .id,
                )
            val counter = board.counter.snapshot()
            val projection = board.bridge.projectionStateSnapshot()
            board.bridge.cutCoordinator.afterBlockingMaterialization = { error("materialization failed") }
            val failure = AtomicReference<Throwable>()

            Thread {
                runCatching {
                    board.bridge.cutCoordinator.awaitNumeric(BlockingInteraction.Numeric(sourceId, 0, 2, 1), 3_000)
                }.onFailure(failure::set)
            }.also {
                it.start()
                it.join(3_000)
            }
            board.bridge.cutCoordinator.afterBlockingMaterialization = null

            assertSoftly {
                failure.get().shouldBeInstanceOf<PlaybackTerminalFailure>()
                board.counter.currentMsgId() shouldBeGreaterThan counter.currentMsgId
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.bridge.cutCoordinator.drain(SeatId(1)) shouldBe emptyList()
                board.bridge.cutCoordinator.currentBlockingInteraction() shouldBe null
            }
        }

        test("stale commander cleanup keeps ids monotonic without replacing competing state") {
            val board = startPuzzleAtMain1(puzzle)
            board.bridge.prioritySignal.awaitSignal(0)
            board.bridge.cutCoordinator.drain(SeatId(1))
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first()
            val sourceId = ForgeCardId(source.id)
            val promptInstanceId = board.bridge.reserveInstanceId().value
            val seeded =
                board.bridge
                    .projectionStateSnapshot()
                    .editor()
                    .also { it.limboInstanceIds += promptInstanceId }
                    .freeze()
            board.bridge.replaceProjectionStateForTest(seeded)
            val interaction =
                BlockingInteraction.Optional(
                    sourceId,
                    true,
                    null,
                    CommanderReturnPromptContext(
                        board.bridge.getOrAllocInstanceId(sourceId).value,
                        promptInstanceId,
                        CommanderZone.Battlefield,
                        CommanderZone.Graveyard,
                        1,
                        "Destroy",
                    ),
                )
            val failure = AtomicReference<Throwable>()
            val waiter =
                Thread {
                    runCatching { board.bridge.cutCoordinator.awaitOptional(interaction, 3_000, false) }.onFailure(failure::set)
                }.also(Thread::start)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var pending = board.bridge.cutCoordinator.currentBlockingInteraction()
            while (pending == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                pending = board.bridge.cutCoordinator.currentBlockingInteraction()
            }
            val exact = checkNotNull(pending)
            exact.interaction shouldBe interaction
            board.bridge.cutCoordinator.drain(SeatId(1))
            val counter = board.counter.snapshot()
            board.bridge.projectionStateSnapshot().limboInstanceIds shouldContain promptInstanceId
            val beforeCompeting = board.bridge.projectionStateSnapshot()
            val competing =
                board.bridge
                    .projectionStateSnapshot()
                    .editor()
                    .freeze()
            competing.revision shouldBe beforeCompeting.revision + 1
            board.bridge.cutCoordinator.beforeCommanderCleanupInstall = {
                board.bridge.replaceProjectionStateForTest(competing)
            }

            shouldThrow<PlaybackTerminalFailure> {
                board.bridge.cutCoordinator.submitOptionalAnswer(exact.interactionId, exact.gameStateId, true)
            }
            board.bridge.cutCoordinator.beforeCommanderCleanupInstall = null
            waiter.join(3_000)

            assertSoftly {
                waiter.isAlive shouldBe false
                board.counter.currentMsgId() shouldBeGreaterThan counter.currentMsgId
                board.bridge.projectionStateSnapshot() shouldBe competing
                board.bridge.cutCoordinator.drain(SeatId(1)) shouldBe emptyList()
                failure.get().shouldBeInstanceOf<PlaybackTerminalFailure>()
            }
        }

        test("damage interaction commits before signal and returns live handles only on engine thread") {
            val board = startPuzzleAtMain1(puzzle)
            board.bridge.prioritySignal.awaitSignal(0)
            board.bridge.cutCoordinator.drain(SeatId(1))
            val attacker =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first()
            val blocker =
                board.ai
                    .getZone(ZoneType.Library)
                    .cards
                    .first()
            val enteredInstall = CountDownLatch(1)
            val releaseInstall = CountDownLatch(1)
            board.bridge.cutCoordinator.beforeBlockingInstall = {
                enteredInstall.countDown()
                check(releaseInstall.await(3, TimeUnit.SECONDS))
            }
            val returned = AtomicReference<MutableMap<forge.game.card.Card?, Int>?>()
            val thrown = AtomicReference<Throwable>()
            val interaction =
                BlockingInteraction.Damage.of(
                    attackerId = ForgeCardId(attacker.id),
                    blockerIds = listOf(ForgeCardId(blocker.id)),
                    damageDealt = 2,
                    hasDeathtouch = false,
                    hasTrample = false,
                    hasDefender = true,
                )
            val engine =
                Thread {
                    try {
                        returned.set(
                            board.bridge.cutCoordinator.awaitDamage(
                                interaction,
                                attacker,
                                forge.game.card.CardCollection(listOf(blocker)),
                                board.ai,
                                timeoutMs = 3_000,
                                fallback = { null },
                            ),
                        )
                    } catch (failure: Throwable) {
                        thrown.set(failure)
                    }
                }
            engine.start()
            check(enteredInstall.await(3, TimeUnit.SECONDS))

            board.bridge.prioritySignal.awaitSignal(0) shouldBe false
            val drained = AtomicReference<List<List<wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage>>>()
            val drainReturned = CountDownLatch(1)
            val session =
                Thread {
                    drained.set(board.bridge.cutCoordinator.drain(SeatId(1)))
                    drainReturned.countDown()
                }
            session.start()
            drainReturned.await(100, TimeUnit.MILLISECONDS) shouldBe false

            releaseInstall.countDown()
            board.bridge.prioritySignal.awaitSignal(3_000) shouldBe true
            val published =
                board.bridge.cutCoordinator
                    .currentBlockingInteraction()
                    .shouldNotBeNull()
            check(drainReturned.await(3, TimeUnit.SECONDS))
            drained.get().flatten().any { it.hasAssignDamageReq() } shouldBe true
            board.bridge.cutCoordinator.submitDamageAnswer(
                published.interactionId,
                published.gameStateId,
                listOf(DamageAssignmentValue(ForgeCardId(attacker.id), mapOf(ForgeCardId(blocker.id) to 2))),
            ) shouldBe true
            engine.join(3_000)
            board.bridge.cutCoordinator.beforeBlockingInstall = null

            thrown.get() shouldBe null
            returned.get()?.get(blocker) shouldBe 2
        }

        test("later damage assigner resolves against its own engine-thread blocker handles") {
            val board =
                startPuzzleAtMain1(
                    puzzle
                        .replace(
                            "humanbattlefield=Forest",
                            "humanbattlefield=Grizzly Bears;Grizzly Bears",
                        ).replace(
                            "ailibrary=Forest",
                            "aibattlefield=Grizzly Bears;Grizzly Bears\nailibrary=Forest",
                        ),
                )
            board.bridge.cutCoordinator.drain(SeatId(1))
            val attackers =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.name == "Grizzly Bears" }
            val blockers =
                board.ai
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.name == "Grizzly Bears" }
            val firstInteraction =
                BlockingInteraction.Damage.of(
                    ForgeCardId(attackers[0].id),
                    listOf(ForgeCardId(blockers[0].id)),
                    damageDealt = 2,
                    hasDeathtouch = false,
                    hasTrample = false,
                    hasDefender = true,
                )
            val firstResult = AtomicReference<MutableMap<forge.game.card.Card?, Int>?>()
            val engine =
                Thread {
                    firstResult.set(
                        board.bridge.cutCoordinator.awaitDamage(
                            firstInteraction,
                            attackers[0],
                            forge.game.card.CardCollection(listOf(blockers[0])),
                            board.ai,
                            timeoutMs = 3_000,
                            fallback = { null },
                        ),
                    )
                }
            engine.start()
            while (board.bridge.cutCoordinator.currentBlockingInteraction() == null) Thread.onSpinWait()
            val published =
                board.bridge.cutCoordinator
                    .currentBlockingInteraction()
                    .shouldNotBeNull()

            board.bridge.cutCoordinator.submitDamageAnswer(
                published.interactionId,
                published.gameStateId,
                listOf(
                    DamageAssignmentValue(ForgeCardId(attackers[0].id), mapOf(ForgeCardId(blockers[0].id) to 2)),
                    DamageAssignmentValue(ForgeCardId(attackers[1].id), mapOf(ForgeCardId(blockers[1].id) to 2)),
                ),
            ) shouldBe true
            engine.join(3_000)

            firstResult.get()?.keys?.single() shouldBeSameInstanceAs blockers[0]
            val later =
                board.bridge.cutCoordinator
                    .takeCachedDamage(
                        attackers[1],
                        forge.game.card.CardCollection(listOf(blockers[1])),
                    ).shouldNotBeNull()
            later.keys.single() shouldBeSameInstanceAs blockers[1]
            later[null] shouldBe null
        }

        test("teardown wakes blocking interaction and rejects later publication") {
            val board = startPuzzleAtMain1(puzzle)
            board.bridge.cutCoordinator.drain(SeatId(1))
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first()
            val interaction = BlockingInteraction.Optional(ForgeCardId(source.id), false, null, null)
            val thrown = AtomicReference<Throwable>()
            val engine =
                Thread {
                    try {
                        board.bridge.cutCoordinator.awaitOptional(interaction, timeoutMs = 30_000, defaultOnTimeout = false)
                    } catch (failure: Throwable) {
                        thrown.set(failure)
                    }
                }
            engine.start()
            while (board.bridge.cutCoordinator.currentBlockingInteraction() == null) Thread.onSpinWait()

            board.bridge.cutCoordinator.shutdown()
            engine.join(3_000)

            assertSoftly {
                thrown.get().shouldBeInstanceOf<PlaybackTerminalFailure>()
                board.bridge.cutCoordinator.currentBlockingInteraction() shouldBe null
                shouldThrow<PlaybackTerminalFailure> {
                    board.bridge.cutCoordinator.awaitOptional(interaction, timeoutMs = 1, defaultOnTimeout = false)
                } shouldBe board.bridge.cutCoordinator.failure()
            }
        }
    })
