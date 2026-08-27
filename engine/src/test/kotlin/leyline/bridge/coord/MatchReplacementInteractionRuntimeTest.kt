package leyline.bridge.coord

import forge.game.replacement.ReplacementEffect
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ReplacementInteractionResult
import leyline.bridge.handoff.ReplacementKeywordKind
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.SelectReplacementResp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchReplacementInteractionRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:replacement runtime
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Fiery Temper;Fiery Temper
            humanbattlefield=Island
            ailibrary=Forest
            """.trimIndent()

        fun effects(board: Board): List<ReplacementEffect> =
            board.human
                .getZone(ZoneType.Hand)
                .cards
                .sortedBy { it.id }
                .flatMap { card -> card.keywords.flatMap { it.replacements } }

        fun request(effects: List<ReplacementEffect>): PromptRequest =
            PromptRequest(
                promptType = "select_replacement",
                message = "Choose which replacement effect applies first",
                options = effects.map { it.toString() },
                min = 1,
                max = 1,
                defaultIndex = 0,
                route = PromptRouteResolver.resolve(PromptSemantic.SelectReplacement),
            )

        fun awaitPublished(coordinator: MatchCutCoordinator): leyline.bridge.handoff.PublishedReplacementInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.replacement.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.replacement.current()
            }
            return checkNotNull(published)
        }

        test("captures only ordered distinct Madness self-replacements") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            val all = effects(board)
            val initial = ReplacementWindowCapture(coordinator).initial(request(all), all)
            initial.shouldNotBeNull()
            assertSoftly {
                initial.value.options.map { it.keyword } shouldBe
                    listOf(ReplacementKeywordKind.Madness, ReplacementKeywordKind.Madness)
                initial.value.options.map { it.originalOptionIndex } shouldBe listOf(0, 1)
                initial.value.options
                    .map { it.hostForgeCardId }
                    .distinct() shouldHaveSize 2
                (initial.handlesByOption[0] === all[0]) shouldBe true
                (initial.handlesByOption[1] === all[1]) shouldBe true
            }
        }

        test("publishes the exact row envelope and returns the retained handle") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val all = effects(board)
            val result = AtomicReference<ReplacementInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.replacement.awaitReplacement(request(all), all, 3_000))
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val batch = coordinator.drain(SeatId(1)).flatten()
            val req = batch.single { it.hasSelectReplacementReq() }
            val rows = req.selectReplacementReq.replacementsList
            val state = batch.firstOrNull { it.hasGameStateMessage() }
            val baseline = board.bridge.projectionStateSnapshot()
            val sequence = board.counter.snapshot()
            val acceptedBefore = board.bridge.responseAcceptance.responsesAccepted()
            assertSoftly {
                req.type shouldBe GREMessageType.SelectReplacementReq_695e
                req.gameStateId shouldBe published.gameStateId
                req.prompt.promptId shouldBe PromptIds.SELECT_REPLACEMENT
                req.prompt.parametersCount shouldBe 0
                req.allowCancel shouldBe AllowCancel.No_a526
                req.allowUndo shouldBe true
                state?.gameStateMessage?.pendingMessageCount shouldBe 1
                rows shouldHaveSize 2
                rows.forEach { row ->
                    row.objectInstance shouldBe row.affectedObject
                    row.objectInstance shouldBeGreaterThan 0
                    row.uniqueAbilityId shouldBeGreaterThan 0
                    row.abilityGrpId shouldBeGreaterThan 0
                    row.replacementEffectId shouldBeGreaterThan 0
                    row.conferringObjectZcid shouldBe 0
                }
                rows.map { it.replacementEffectId }.distinct() shouldHaveSize 2
                req.selectReplacementReq.isOptional shouldBe false
                req.selectReplacementReq.replacementsType.number shouldBe 0
                req.selectReplacementReq.gameObjectSelectionsCount shouldBe 0
                coordinator.acceptSettled(
                    ClientToGREMessage.newBuilder().setType(ClientMessageType.SearchResp_097b).build(),
                    published.gameStateId,
                ) shouldBe false
                board.bridge.projectionStateSnapshot() shouldBe baseline
                board.counter.snapshot() shouldBe sequence
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedBefore
                coordinator.replacement.current().shouldNotBeNull()
                coordinator.acceptSettled(
                    ClientToGREMessage
                        .newBuilder()
                        .setType(ClientMessageType.SelectReplacementResp_097b)
                        .setSelectReplacementResp(SelectReplacementResp.newBuilder().setReplacement(rows[0]))
                        .build(),
                    published.gameStateId + 1,
                ) shouldBe false
                coordinator.acceptSettled(
                    ClientToGREMessage
                        .newBuilder()
                        .setType(ClientMessageType.SelectReplacementResp_097b)
                        .setSelectReplacementResp(SelectReplacementResp.newBuilder().setReplacement(rows[0]))
                        .build(),
                    published.gameStateId,
                    req.msgId + 1,
                ) shouldBe false
                coordinator.acceptSettled(
                    ClientToGREMessage
                        .newBuilder()
                        .setType(ClientMessageType.SelectReplacementResp_097b)
                        .setSelectReplacementResp(SelectReplacementResp.newBuilder().setReplacement(rows[0].toBuilder().setAbilityGrpId(0)))
                        .build(),
                    published.gameStateId,
                ) shouldBe false
                board.bridge.projectionStateSnapshot() shouldBe baseline
                board.counter.snapshot() shouldBe sequence
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedBefore
                coordinator.acceptSettled(
                    ClientToGREMessage
                        .newBuilder()
                        .setType(ClientMessageType.SelectReplacementResp_097b)
                        .setSelectReplacementResp(SelectReplacementResp.newBuilder().setReplacement(rows[0]))
                        .build(),
                    published.gameStateId,
                ) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                (result.get().handle === all[0]) shouldBe true
                result.get().optionIndex shouldBe 0
                coordinator.replacement.current().shouldBeNull()
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedBefore + 1
                coordinator.acceptSettled(
                    wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
                        .newBuilder()
                        .setType(ClientMessageType.SelectReplacementResp_097b)
                        .setSelectReplacementResp(SelectReplacementResp.newBuilder().setReplacement(rows[0]))
                        .build(),
                    published.gameStateId,
                ) shouldBe false
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedBefore + 1
            }
        }

        test("timeout returns the first retained handle and retires the slot") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val all = effects(board)
            val bridge =
                leyline.bridge.handoff.InteractivePromptBridge(timeoutMs = 25, strict = false).also {
                    it.runtimeBindings = coordinator.prompts.bindings(SeatId(1))
                }
            val result = bridge.requestReplacement(request(all), all)
            result.shouldNotBeNull()
            assertSoftly {
                (result.handle === all[0]) shouldBe true
                result.timedOut shouldBe true
                coordinator.replacement.current().shouldBeNull()
            }
        }

        test("central reset clears a pending replacement slot") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val all = effects(board)
            Thread { coordinator.replacement.awaitReplacement(request(all), all, 3_000) }.start()
            val published = awaitPublished(coordinator)
            val row =
                coordinator
                    .drain(SeatId(1))
                    .flatMap { it }
                    .single { it.hasSelectReplacementReq() }
                    .selectReplacementReq
                    .replacementsList
                    .first()
            coordinator.prompts.settled.reset()
            coordinator.replacement.current().shouldBeNull()
            coordinator.acceptSettled(
                wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
                    .newBuilder()
                    .setType(ClientMessageType.SelectReplacementResp_097b)
                    .setSelectReplacementResp(SelectReplacementResp.newBuilder().setReplacement(row))
                    .build(),
                published.gameStateId,
            ) shouldBe false
        }
    })
