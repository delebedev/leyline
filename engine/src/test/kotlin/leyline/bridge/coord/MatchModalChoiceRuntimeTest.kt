package leyline.bridge.coord

import forge.game.ability.ApiType
import forge.game.ability.effects.CharmEffect
import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.ModalChoiceInteractionResult
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PublishedModalChoiceInteraction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchModalChoiceRuntimeTest :
    BoardTest({
        fun source(board: Board): Card =
            board.human
                .getZone(ZoneType.Hand)
                .cards
                .single { it.name == "Trufflesnout" }

        fun ability(card: Card): SpellAbility = card.triggers.mapNotNull { it.ensureAbility() }.first { it.api == ApiType.Charm }

        fun possible(sa: SpellAbility): List<AbilitySub> = CharmEffect.makePossibleOptions(sa).toList()

        fun request(
            options: List<AbilitySub>,
            min: Int = 1,
            max: Int = 1,
        ): PromptRequest =
            PromptRequest(
                promptType = "choose_mode",
                message = "Choose a mode",
                options = options.map { it.description ?: it.toString() },
                min = min,
                max = max,
                route = PromptRouteResolver.resolve(PromptSemantic.ModalChoice),
            )

        fun awaitPublished(coordinator: MatchCutCoordinator): PublishedModalChoiceInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.modalChoices.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.modalChoices.current()
            }
            return checkNotNull(published)
        }

        test("publishes one atomic CTO cut and retains exact Forge handle identity") {
            val board = startPuzzleAtMain1FromResource("puzzles/modal-etb.pzl")
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val card = source(board)
            val sa = ability(card)
            val options = possible(sa)
            sa.activatingPlayer = board.human
            val projectionBefore = board.bridge.projectionStateSnapshot()
            val result = AtomicReference<ModalChoiceInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.modalChoiceRuntime(SeatId(1)).awaitSelection(request(options), options, card, sa, 3_000))
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            board.bridge.hasPendingNonActionInteraction() shouldBe true
            val batch = coordinator.drain(SeatId(1)).single()
            val cto = batch.single { it.type == GREMessageType.CastingTimeOptionsReq_695e }.castingTimeOptionsReq
            val optionGrpIds =
                cto
                    .getCastingTimeOptionReq(0)
                    .modalReq.modalOptionsList
                    .map { it.grpId }
            assertSoftly {
                batch.map { it.type } shouldContainExactly
                    listOf(GREMessageType.GameStateMessage_695e, GREMessageType.CastingTimeOptionsReq_695e)
                cto.getCastingTimeOptionReq(0).modalReq.modalOptionsCount shouldBe options.size
                val committedBaseline =
                    board
                        .bridge
                        .projectionStateSnapshot()
                        .viewerCursors[1]
                        ?.previousSnapshot
                        ?: projectionBefore.viewerCursors[1]?.previousSnapshot
                committedBaseline?.stack?.entries?.none { it.forgeAbilityId == sa.id } ?: true shouldBe true
                coordinator.modalChoices.submit(published.interactionId, published.gameStateId, listOf(optionGrpIds[1])) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                board.bridge.hasPendingNonActionInteraction() shouldBe false
                result.get().handles.single() shouldBe options[1]
                (result.get().handles.single() === options[1]) shouldBe true
                coordinator.modalChoices.submit(published.interactionId, published.gameStateId, listOf(999999)) shouldBe false
            }
            assertSoftly {
                coordinator.modalChoices.releaseAfterEngineResume(published.interactionId) shouldBe true
                board.bridge.resolvePendingTriggerAbilityIdentity(1, ForgeCardId(card.id)) { optionGrpIds[0] } shouldBe optionGrpIds[1]
            }
        }

        test("rejects wrong game-state, duplicate, and late responses; timeout chooses default") {
            val board = startPuzzleAtMain1FromResource("puzzles/modal-etb.pzl")
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val card = source(board)
            val sa = ability(card)
            val options = possible(sa)
            sa.activatingPlayer = board.human
            val result = AtomicReference<ModalChoiceInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                try {
                    result.set(coordinator.modalChoiceRuntime(SeatId(1)).awaitSelection(request(options), options, card, sa, 25))
                } finally {
                    finished.countDown()
                }
            }.start()
            val published = awaitPublished(coordinator)
            val grpIds =
                coordinator
                    .drain(SeatId(1))
                    .single()
                    .last()
                    .castingTimeOptionsReq
                    .getCastingTimeOptionReq(0)
                    .modalReq
                    .modalOptionsList
                    .map { it.grpId }
            assertSoftly {
                coordinator.modalChoices.submit(published.interactionId, published.gameStateId + 1, listOf(grpIds[0])) shouldBe false
                coordinator.modalChoices.submit("${published.interactionId}-stale", published.gameStateId, listOf(grpIds[0])) shouldBe false
                coordinator.modalChoices.submit(published.interactionId, published.gameStateId, listOf(Int.MAX_VALUE)) shouldBe false
                coordinator.modalChoices.submit(published.interactionId, published.gameStateId, emptyList()) shouldBe false
                coordinator.modalChoices.submit(published.interactionId, published.gameStateId, listOf(grpIds[0], grpIds[0])) shouldBe false
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().timedOut shouldBe true
                coordinator.modalChoices.current().shouldBeNull()
                coordinator.modalChoices.submit(published.interactionId, published.gameStateId, listOf(grpIds[0])) shouldBe false
            }
        }

        test("detaches a completed modal before the next window and releases cleanup by interaction") {
            val board = startPuzzleAtMain1FromResource("puzzles/modal-etb.pzl")
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val card = source(board)
            val sa = ability(card)
            val options = possible(sa)
            sa.activatingPlayer = board.human

            fun open(
                result: AtomicReference<ModalChoiceInteractionResult>,
                finished: CountDownLatch,
                drainBatch: Boolean = true,
                fallbackGrpIds: List<Int> = emptyList(),
            ): Pair<PublishedModalChoiceInteraction, List<Int>> {
                Thread {
                    try {
                        result.set(coordinator.modalChoiceRuntime(SeatId(1)).awaitSelection(request(options), options, card, sa, 3_000))
                    } finally {
                        finished.countDown()
                    }
                }.start()
                val published = awaitPublished(coordinator)
                val optionGrpIds =
                    if (drainBatch) {
                        val batch = coordinator.drain(SeatId(1)).single()
                        batch
                            .single { it.type == GREMessageType.CastingTimeOptionsReq_695e }
                            .castingTimeOptionsReq
                            .getCastingTimeOptionReq(0)
                            .modalReq
                            .modalOptionsList
                            .map { it.grpId }
                    } else {
                        fallbackGrpIds
                    }
                return published to optionGrpIds
            }

            val firstResult = AtomicReference<ModalChoiceInteractionResult>()
            val firstFinished = CountDownLatch(1)
            val (first, firstGrpIds) = open(firstResult, firstFinished)
            coordinator.modalChoices.submit(first.interactionId, first.gameStateId, listOf(firstGrpIds[0])) shouldBe true
            firstFinished.await(3, TimeUnit.SECONDS) shouldBe true

            val secondResult = AtomicReference<ModalChoiceInteractionResult>()
            val secondFinished = CountDownLatch(1)
            val (second, secondGrpIds) = open(secondResult, secondFinished, drainBatch = false, fallbackGrpIds = firstGrpIds)
            assertSoftly {
                coordinator.modalChoices.releaseAfterEngineResume(first.interactionId) shouldBe true
                coordinator.modalChoices.releaseAfterEngineResume(first.interactionId) shouldBe false
                coordinator.modalChoices.current()?.interactionId shouldBe second.interactionId
                coordinator.modalChoices.submit(second.interactionId, second.gameStateId, listOf(secondGrpIds[0])) shouldBe true
                coordinator.modalChoices.releaseAfterEngineResume(second.interactionId) shouldBe true
                secondFinished.await(3, TimeUnit.SECONDS) shouldBe true
                (secondResult.get().handles.single() === options[0]) shouldBe true

                val queued = coordinator.drain(SeatId(1))
                queued.size shouldBe 3
                queued.drop(1).all { it.single().type == GREMessageType.GameStateMessage_695e } shouldBe true
            }
        }

        test("correlated cancel completes empty without staging a modal selection") {
            val board = startPuzzleAtMain1FromResource("puzzles/modal-etb.pzl")
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val card = source(board)
            val sa = ability(card)
            val options = possible(sa)
            sa.activatingPlayer = board.human
            val result = AtomicReference<ModalChoiceInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                try {
                    result.set(coordinator.modalChoiceRuntime(SeatId(1)).awaitSelection(request(options), options, card, sa, null))
                } finally {
                    finished.countDown()
                }
            }.start()
            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))

            assertSoftly {
                coordinator.modalChoices.cancel(published.interactionId, published.gameStateId + 1) shouldBe false
                coordinator.modalChoices.cancel(published.interactionId, published.gameStateId) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().optionIndices shouldBe emptyList()
                result.get().handles shouldBe emptyList()
                coordinator.modalChoices.current().shouldBeNull()
                coordinator.modalChoices.releaseAfterEngineResume(published.interactionId) shouldBe true
                coordinator.modalChoices.cancel(published.interactionId, published.gameStateId) shouldBe false
                board.bridge.resolvePendingTriggerAbilityIdentity(1, ForgeCardId(card.id)) { 0 } shouldBe 0
            }
        }

        test("materialization failure terminates the coordinator and retains diagnostic") {
            val board = startPuzzleAtMain1FromResource("puzzles/modal-etb.pzl")
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val card = source(board)
            val sa = ability(card)
            sa.activatingPlayer = board.human
            val request = request(possible(sa))
            shouldThrow<IllegalStateException> {
                coordinator.modalChoiceRuntime(SeatId(1)).awaitSelection(
                    request,
                    possible(sa),
                    Card(999999, null).also { it.name = card.name },
                    sa,
                    3_000,
                )
            }
            coordinator
                .failure()
                ?.modalChoiceDiagnostic
                ?.interaction
                ?.sourceForgeCardId shouldBe ForgeCardId(999999)
        }

        test("enqueue failure retains the attempted modal cut without output") {
            val board = startPuzzleAtMain1FromResource("puzzles/modal-etb.pzl")
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val card = source(board)
            val sa = ability(card)
            val options = possible(sa)
            sa.activatingPlayer = board.human
            coordinator.setBeforeBatchEnqueue(SeatId(1)) { _, _ -> error("modal choice feed unavailable") }

            val failure =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.modalChoiceRuntime(SeatId(1)).awaitSelection(request(options), options, card, sa, 3_000)
                }

            assertSoftly {
                failure.pendingModalChoiceCut.shouldNotBeNull()
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                coordinator.modalChoices.current().shouldBeNull()
            }
        }

        test("stale install removes modal output and keeps competing projection") {
            val board = startPuzzleAtMain1FromResource("puzzles/modal-etb.pzl")
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val card = source(board)
            val sa = ability(card)
            val options = possible(sa)
            sa.activatingPlayer = board.human
            val competing =
                board.bridge
                    .projectionStateSnapshot()
                    .editor()
                    .freeze()
            coordinator.modalChoices.beforeInstall = { board.bridge.replaceProjectionStateForTest(competing) }

            val failure =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.modalChoiceRuntime(SeatId(1)).awaitSelection(request(options), options, card, sa, 3_000)
                }

            assertSoftly {
                failure.pendingModalChoiceCut.shouldNotBeNull()
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                board.bridge.projectionStateSnapshot() shouldBe competing
                coordinator.modalChoices.current().shouldBeNull()
            }
        }

        test("post-install failure retains committed modal output and projection") {
            val board = startPuzzleAtMain1FromResource("puzzles/modal-etb.pzl")
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val card = source(board)
            val sa = ability(card)
            val options = possible(sa)
            sa.activatingPlayer = board.human
            val prior = board.bridge.projectionStateSnapshot()
            coordinator.modalChoices.afterInstall = { error("modal acknowledgement unavailable") }

            val failure =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.modalChoiceRuntime(SeatId(1)).awaitSelection(request(options), options, card, sa, 3_000)
                }
            val retained = coordinator.drain(SeatId(1)).single()

            assertSoftly {
                failure.pendingModalChoiceCut.shouldNotBeNull().messages shouldBe retained
                board.bridge.projectionStateSnapshot().revision shouldBe prior.revision + 1
                coordinator.modalChoices.current().shouldBeNull()
            }
        }

        test("response wins the timeout claim and only one terminal result is delivered") {
            val board = startPuzzleAtMain1FromResource("puzzles/modal-etb.pzl")
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val timeoutEntered = CountDownLatch(1)
            val releaseTimeout = CountDownLatch(1)
            coordinator.modalChoices.beforeTimeoutClaim = {
                timeoutEntered.countDown()
                check(releaseTimeout.await(3, TimeUnit.SECONDS))
            }
            val result = AtomicReference<ModalChoiceInteractionResult>()
            val finished = CountDownLatch(1)
            val card = source(board)
            val sa = ability(card)
            val options = possible(sa)
            sa.activatingPlayer = board.human
            Thread {
                result.set(coordinator.modalChoiceRuntime(SeatId(1)).awaitSelection(request(options), options, card, sa, 25))
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val grpId =
                coordinator
                    .drain(
                        SeatId(1),
                    ).single()
                    .last()
                    .castingTimeOptionsReq
                    .getCastingTimeOptionReq(0)
                    .modalReq
                    .getModalOptions(1)
                    .grpId
            timeoutEntered.await(3, TimeUnit.SECONDS) shouldBe true
            coordinator.modalChoices.submit(published.interactionId, published.gameStateId, listOf(grpId)) shouldBe true
            releaseTimeout.countDown()

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().timedOut shouldBe false
                (result.get().handles.single() === options[1]) shouldBe true
                coordinator.failure() shouldBe null
            }
        }

        test("teardown wakes the waiter and rejects a late response") {
            val board = startPuzzleAtMain1FromResource("puzzles/modal-etb.pzl")
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val failure = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            val card = source(board)
            val sa = ability(card)
            val options = possible(sa)
            sa.activatingPlayer = board.human
            Thread {
                runCatching {
                    coordinator.modalChoiceRuntime(SeatId(1)).awaitSelection(request(options), options, card, sa, null)
                }.onFailure(failure::set)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            val cause = IllegalStateException("match closed")
            coordinator.shutdown(cause)

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                failure.get().shouldBeInstanceOf<PlaybackTerminalFailure>().cause shouldBe cause
                coordinator.modalChoices.current().shouldBeNull()
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.modalChoices.submit(published.interactionId, published.gameStateId, emptyList())
                } shouldBe coordinator.failure()
            }
        }

        test("delivery failure terminalizes before a concurrent response can claim the window") {
            val board = startPuzzleAtMain1FromResource("puzzles/modal-etb.pzl")
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val card = source(board)
            val sa = ability(card)
            val options = possible(sa)
            sa.activatingPlayer = board.human
            val engineFailure = AtomicReference<Throwable>()
            val engineFinished = CountDownLatch(1)
            Thread {
                runCatching {
                    coordinator.modalChoiceRuntime(SeatId(1)).awaitSelection(request(options), options, card, sa, null)
                }.onFailure(engineFailure::set)
                engineFinished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val committed = coordinator.drain(SeatId(1)).single()
            val cutLocated = CountDownLatch(1)
            val releaseDelivery = CountDownLatch(1)
            coordinator.modalChoices.afterDeliveryCutLookup = {
                cutLocated.countDown()
                check(releaseDelivery.await(3, TimeUnit.SECONDS))
            }
            val cause = IllegalStateException("modal delivery unavailable")
            val deliveryFailure = AtomicReference<Throwable>()
            val deliveryFinished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.failDelivery(cause) }.onFailure(deliveryFailure::set)
                deliveryFinished.countDown()
            }.start()
            cutLocated.await(3, TimeUnit.SECONDS) shouldBe true
            val responseFailure = AtomicReference<Throwable>()
            val responseFinished = CountDownLatch(1)
            Thread {
                runCatching {
                    coordinator.modalChoices.submit(published.interactionId, published.gameStateId, listOf(0))
                }.onFailure(responseFailure::set)
                responseFinished.countDown()
            }.start()
            releaseDelivery.countDown()

            assertSoftly {
                deliveryFinished.await(3, TimeUnit.SECONDS) shouldBe true
                responseFinished.await(3, TimeUnit.SECONDS) shouldBe true
                engineFinished.await(3, TimeUnit.SECONDS) shouldBe true
                val terminal = deliveryFailure.get().shouldBeInstanceOf<PlaybackTerminalFailure>()
                terminal.cause shouldBe cause
                terminal.pendingModalChoiceCut.shouldNotBeNull().messages shouldBe committed
                responseFailure.get() shouldBe terminal
                engineFailure.get() shouldBe terminal
                coordinator.modalChoices.current().shouldBeNull()
            }
            coordinator.modalChoices.afterDeliveryCutLookup = null
        }
    })
