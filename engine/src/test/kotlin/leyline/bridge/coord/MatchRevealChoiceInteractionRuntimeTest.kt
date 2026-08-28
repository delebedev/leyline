package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.NonInteractiveScope
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.PublishedRevealChoiceInteraction
import leyline.bridge.handoff.RevealChoiceInteractionResult
import leyline.bridge.handoff.StrictPromptRefusalException
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PrioritySignal
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchRevealChoiceInteractionRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:reveal choice runtime
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

        fun revealed(board: Board): List<Card> =
            board.ai
                .getZone(ZoneType.Hand)
                .cards
                .toList()

        fun source(board: Board): Card =
            board.human
                .getZone(ZoneType.Battlefield)
                .cards
                .single()

        fun revealEntry(board: Board): leyline.bridge.handoff.PromptJournal.RevealEntry {
            board.bridge.promptBridge(SeatId(1)).journal.record(
                PromptSideEffect.RevealStarted(revealed(board).map { ForgeCardId(it.id) }, SeatId(2)),
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
            candidates: List<Card>,
            min: Int = 1,
            max: Int = 1,
            defaultIndex: Int = 0,
        ): PromptRequest =
            PromptRequest(
                promptType = "choose_cards",
                message = "Choose a card to exile",
                options = candidates.map { it.name },
                min = min,
                max = max,
                defaultIndex = defaultIndex,
                candidateRefs =
                    candidates.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Hand.name)
                    },
                route = PromptRouteResolver.resolve(PromptSemantic.RevealChoose),
                sourceEntityId = source(board).id,
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

        test("reveal choice publishes one claimed reveal cut and returns the exact handle") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val fullReveal = revealed(board)
            val candidates = listOf(fullReveal.first())
            val entry = revealEntry(board)
            val result = AtomicReference<RevealChoiceInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(
                    coordinator.revealChoices.awaitSelection(
                        request(board, candidates),
                        candidates,
                        entry,
                        true,
                        3_000,
                    ),
                )
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val batch = coordinator.drain(SeatId(1)).single()
            val state = batch.first().gameStateMessage
            val message = batch.single { it.hasSelectNReq() }
            val req = message.selectNReq
            val projection = board.bridge.projectionStateSnapshot()
            val candidateId =
                projection.identities.forgeIdToInstanceId
                    .getValue(ForgeCardId(candidates.single().id))
                    .value
            val fullIds =
                fullReveal.map {
                    projection.identities.forgeIdToInstanceId
                        .getValue(ForgeCardId(it.id))
                        .value
                }
            val sourceId =
                projection.identities.forgeIdToInstanceId
                    .getValue(ForgeCardId(source(board).id))
                    .value

            assertSoftly {
                batch.map { it.type } shouldContainExactly listOf(GREMessageType.GameStateMessage_695e, GREMessageType.SelectNreq)
                state.pendingMessageCount shouldBe 1
                state.gameObjectsList.count { it.type == GameObjectType.RevealedCard } shouldBe fullReveal.size
                req.idsList shouldContainExactly listOf(candidateId)
                req.unfilteredIdsList shouldContainExactly fullIds
                req.sourceId shouldBe sourceId
                req.context shouldBe SelectionContext.Resolution_a163
                req.listType shouldBe SelectionListType.Dynamic
                req.optionContext shouldBe OptionContext.Resolution_a9d7
                req.validationType shouldBe SelectionValidationType.NonRepeatable
                req.idType shouldBe IdType.InstanceId_ab2c
                req.minSel shouldBe 1
                req.maxSel shouldBe 1
                req.minWeight shouldBe Int.MIN_VALUE
                req.maxWeight shouldBe Int.MAX_VALUE
                req.prompt.promptId shouldBe PromptIds.SELECT_N
                message.prompt.promptId shouldBe PromptIds.SELECT_N
                message.allowCancel shouldBe AllowCancel.No_a526
                coordinator.revealChoices.submit(published.interactionId, published.gameStateId, listOf(candidateId)) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                (result.get().handles.single() === candidates.single()) shouldBe true
                result.get().optionIndices shouldContainExactly listOf(0)
                result.get().timedOut shouldBe false
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .activeRevealEntry()
                    .shouldBeNull()
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .consumeExiledUnderSource(ForgeCardId(candidates.single().id)) shouldBe
                    ForgeCardId(source(board).id)
                coordinator.revealChoices
                    .current()
                    .shouldBeNull()
            }
        }

        test("zero-selectable reveal remains a first-class cut and clears its exact reveal") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val entry = revealEntry(board)
            val result = AtomicReference<RevealChoiceInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(
                    coordinator.revealChoices.awaitSelection(
                        request(board, emptyList(), min = 0, max = 0),
                        emptyList(),
                        entry,
                        false,
                        3_000,
                    ),
                )
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val req =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSelectNReq() }
                    .selectNReq
            assertSoftly {
                req.idsList.shouldBeEmpty()
                req.unfilteredIdsCount shouldBe 2
                req.minSel shouldBe 0
                req.maxSel shouldBe 0
                coordinator.revealChoices.submit(published.interactionId, published.gameStateId, emptyList()) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().handles.shouldBeEmpty()
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .activeRevealEntry()
                    .shouldBeNull()
            }
        }

        test("timeout returns the exact default handle and rejects a late response") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val candidates = revealed(board)
            val entry = revealEntry(board)
            val signal = PrioritySignal()
            val publishedAtTimeout = AtomicReference<PublishedRevealChoiceInteraction>()
            coordinator.revealChoices.beforeTimeoutClaim = {
                publishedAtTimeout.set(checkNotNull(coordinator.revealChoices.current()))
            }
            val prompt =
                InteractivePromptBridge(timeoutMs = 25, prioritySignal = signal, strict = false).also {
                    it.runtimeBindings = coordinator.prompts.bindings(SeatId(1))
                }

            val result =
                prompt.requestRevealChoice(
                    request(board, candidates, defaultIndex = 1),
                    candidates,
                    entry,
                    recordExiledUnderSource = false,
                )
            val requestMessage = coordinator.drain(SeatId(1)).flatten().single { it.hasSelectNReq() }
            val published = checkNotNull(publishedAtTimeout.get())

            assertSoftly {
                result.optionIndices shouldContainExactly listOf(1)
                (result.handles.single() === candidates[1]) shouldBe true
                result.timedOut shouldBe true
                signal.awaitSignal(3_000) shouldBe true
                coordinator.revealChoices
                    .current()
                    .shouldBeNull()
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .activeRevealEntry()
                    .shouldBeNull()
                coordinator.revealChoices.submit(
                    published.interactionId,
                    published.gameStateId,
                    listOf(requestMessage.selectNReq.idsList[0]),
                ) shouldBe false
            }
        }

        test("completion clears only the claimed reveal version") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val candidates = listOf(revealed(board).first())
            val claimed = revealEntry(board)
            val finished = CountDownLatch(1)
            Thread {
                coordinator.revealChoices.awaitSelection(
                    request(board, candidates),
                    candidates,
                    claimed,
                    false,
                    3_000,
                )
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val id =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSelectNReq() }
                    .selectNReq.idsList
                    .single()
            val journal = board.bridge.promptBridge(SeatId(1)).journal
            journal.record(PromptSideEffect.RevealStarted(listOf(ForgeCardId(candidates.single().id)), SeatId(2)))
            val replacement = checkNotNull(journal.activeRevealEntry())

            assertSoftly {
                coordinator.revealChoices.submit(published.interactionId, published.gameStateId, listOf(id)) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                journal.activeRevealEntry() shouldBe replacement
            }
        }

        test("strict fallback refusal clears the exact reveal entry") {
            val board = startPuzzleAtMain1(puzzle)
            val handles = revealed(board)
            val prompt = InteractivePromptBridge(timeoutMs = null, strict = true)
            prompt.journal.record(PromptSideEffect.RevealStarted(handles.map { ForgeCardId(it.id) }, SeatId(2)))
            val claimed = checkNotNull(prompt.journal.activeRevealEntry())

            shouldThrow<StrictPromptRefusalException> {
                NonInteractiveScope.bestEffort {
                    prompt.requestRevealChoice(
                        request(board, handles),
                        handles,
                        claimed,
                        recordExiledUnderSource = false,
                    )
                }
            }

            prompt.journal.activeRevealEntry().shouldBeNull()
        }
    })
