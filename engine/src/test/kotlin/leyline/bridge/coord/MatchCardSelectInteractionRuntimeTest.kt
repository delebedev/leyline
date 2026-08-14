package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.CardSelectInteractionResult
import leyline.bridge.handoff.CardSelectKind
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PublishedCardSelectInteraction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchCardSelectInteractionRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:card select runtime
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

        fun source(board: Board): Card =
            board.human
                .getZone(ZoneType.Battlefield)
                .cards
                .single()

        fun request(
            board: Board,
            semantic: PromptSemantic,
            sourceId: Int? = source(board).id,
            min: Int = 1,
            max: Int = 1,
            defaultIndex: Int = 0,
        ): PromptRequest {
            val cards = options(board)
            return PromptRequest(
                promptType = "choose_cards",
                message = "Choose a card",
                options = cards.map { it.name },
                min = min,
                max = max,
                defaultIndex = defaultIndex,
                candidateRefs =
                    cards.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Hand.name)
                    },
                route = PromptRouteResolver.resolve(semantic),
                sourceEntityId = sourceId,
            )
        }

        fun awaitPublished(coordinator: MatchCutCoordinator): PublishedCardSelectInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.cardSelect.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.cardSelect.current()
            }
            return checkNotNull(published)
        }

        data class Case(
            val semantic: PromptSemantic,
            val kind: CardSelectKind,
            val context: SelectionContext,
            val listType: SelectionListType,
            val optionContext: OptionContext,
            val innerPromptId: Int = PromptIds.SELECT_N,
            val innerParameterId: Int? = null,
            val outerPromptId: Int,
            val allowCancel: AllowCancel,
            val includeRequestSource: Boolean = true,
        )

        val cases =
            listOf(
                Case(
                    PromptSemantic.SelectNLegendRule,
                    CardSelectKind.LegendRule,
                    SelectionContext.Resolution_a163,
                    SelectionListType.Dynamic,
                    OptionContext.Resolution_a9d7,
                    innerPromptId = 0,
                    outerPromptId = PromptIds.SELECT_N_LEGEND_RULE,
                    allowCancel = AllowCancel.No_a526,
                    includeRequestSource = false,
                ),
                Case(
                    PromptSemantic.SelectNDiscard,
                    CardSelectKind.Discard,
                    SelectionContext.Discard_a163,
                    SelectionListType.Static,
                    OptionContext.Payment,
                    innerPromptId = PromptIds.DISCARD_COST,
                    outerPromptId = PromptIds.SELECT_N,
                    allowCancel = AllowCancel.None_a526,
                    includeRequestSource = false,
                ),
                Case(
                    PromptSemantic.SelectNSacrificeEffect,
                    CardSelectKind.SacrificeEffect,
                    SelectionContext.Resolution_a163,
                    SelectionListType.Dynamic,
                    OptionContext.Resolution_a9d7,
                    outerPromptId = PromptIds.SELECT_N,
                    allowCancel = AllowCancel.None_a526,
                ),
                Case(
                    PromptSemantic.SuspectChoice,
                    CardSelectKind.Suspect,
                    SelectionContext.Resolution_a163,
                    SelectionListType.Dynamic,
                    OptionContext.Resolution_a9d7,
                    innerPromptId = 0,
                    innerParameterId = PromptIds.SELECT_N_INNER_PARAMETER,
                    outerPromptId = PromptIds.SUSPECT_ONE_OF_THOSE_CREATURES,
                    allowCancel = AllowCancel.Continue,
                ),
                Case(
                    PromptSemantic.MutateTopBottom,
                    CardSelectKind.MutateTopBottom,
                    SelectionContext.Resolution_a163,
                    SelectionListType.Dynamic,
                    OptionContext.Resolution_a9d7,
                    outerPromptId = PromptIds.SELECT_N,
                    allowCancel = AllowCancel.No_a526,
                ),
            )

        cases.forEach { case ->
            test("${case.semantic} preserves its exact SelectN envelope and handle") {
                val board = startPuzzleAtMain1(puzzle)
                val coordinator = board.bridge.cutCoordinator
                coordinator.drain(SeatId(1))
                val handles = options(board)
                val result = AtomicReference<CardSelectInteractionResult>()
                val finished = CountDownLatch(1)
                Thread {
                    result.set(
                        coordinator.cardSelectRuntime(SeatId(1)).awaitSelection(
                            request(board, case.semantic, sourceId = source(board).id.takeIf { case.includeRequestSource }),
                            handles,
                            3_000,
                        ),
                    )
                    finished.countDown()
                }.start()

                val published = awaitPublished(coordinator)
                val batch = coordinator.drain(SeatId(1)).single()
                val message = batch.single { it.hasSelectNReq() }
                val req = message.selectNReq
                assertSoftly {
                    batch.map { it.type } shouldContainExactly
                        listOf(
                            wotc.mtgo.gre.external.messaging.Messages.GREMessageType.GameStateMessage_695e,
                            wotc.mtgo.gre.external.messaging.Messages.GREMessageType.SelectNreq,
                        )
                    batch.first().gameStateMessage.pendingMessageCount shouldBe 1
                    published.kind shouldBe case.kind
                    req.context shouldBe case.context
                    req.listType shouldBe case.listType
                    req.optionContext shouldBe case.optionContext
                    req.validationType shouldBe SelectionValidationType.NonRepeatable
                    req.idType shouldBe IdType.InstanceId_ab2c
                    req.minSel shouldBe 1
                    req.maxSel shouldBe 1
                    req.minWeight shouldBe Int.MIN_VALUE
                    req.maxWeight shouldBe Int.MAX_VALUE
                    req.prompt.promptId shouldBe case.innerPromptId
                    case.innerParameterId?.let {
                        req.prompt.parametersList
                            .single()
                            .promptId shouldBe it
                    }
                    message.prompt.promptId shouldBe case.outerPromptId
                    message.allowCancel shouldBe case.allowCancel
                    req.sourceId shouldBe
                        when (case.kind) {
                            CardSelectKind.LegendRule -> PromptIds.SELECT_N_LEGEND_RULE_SOURCE
                            CardSelectKind.Discard -> 0
                            CardSelectKind.SacrificeEffect,
                            CardSelectKind.Suspect,
                            CardSelectKind.MutateTopBottom,
                            -> board.bridge.getOrAllocInstanceId(ForgeCardId(source(board).id)).value
                        }
                    if (case.kind == CardSelectKind.LegendRule) {
                        message.prompt.parametersList.map { it.numberValue } shouldContainExactly listOf(0)
                    }
                    coordinator.cardSelect.submitSelectN(
                        published.interactionId,
                        published.gameStateId,
                        listOf(req.idsList[1]),
                    ) shouldBe true
                    finished.await(3, TimeUnit.SECONDS) shouldBe true
                    result.get().optionIndices shouldContainExactly listOf(1)
                    (result.get().handles.single() === handles[1]) shouldBe true
                    coordinator.cardSelect.current().shouldBeNull()
                }
            }
        }

        listOf(PromptSemantic.SelectNSacrificeEffect to 1, PromptSemantic.SuspectChoice to 2).forEach { (semantic, sentiment) ->
            test("$semantic stages its choice fact before the exact handle returns") {
                val board = startPuzzleAtMain1(puzzle)
                val coordinator = board.bridge.cutCoordinator
                coordinator.drain(SeatId(1))
                val handles = options(board)
                val finished = CountDownLatch(1)
                Thread {
                    coordinator.cardSelectRuntime(SeatId(1)).awaitSelection(request(board, semantic), handles, 3_000)
                    finished.countDown()
                }.start()
                val published = awaitPublished(coordinator)
                val id =
                    coordinator
                        .drain(SeatId(1))
                        .flatten()
                        .single { it.hasSelectNReq() }
                        .selectNReq.idsList[1]

                coordinator.cardSelect.submitSelectN(
                    published.interactionId,
                    published.gameStateId,
                    listOf(id),
                ) shouldBe true
                val result =
                    board.bridge
                        .promptBridge(SeatId(1))
                        .journal
                        .snapshotChoiceResults()
                        .single()
                        .result
                assertSoftly {
                    result.sourceForgeCardId.value shouldBe source(board).id
                    result.choiceValue shouldBe id
                    result.sentiment shouldBe sentiment
                    finished.await(3, TimeUnit.SECONDS) shouldBe true
                }
            }
        }

        test("Legend Rule timeout returns its exact default handle and rejects a late response") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val handles = options(board)
            var timedOut = false
            val publishedAtTimeout = AtomicReference<PublishedCardSelectInteraction>()
            coordinator.cardSelect.beforeTimeoutClaim = {
                publishedAtTimeout.set(checkNotNull(coordinator.cardSelect.current()))
            }
            val prompt =
                InteractivePromptBridge(timeoutMs = 25, strict = false).also {
                    it.cardSelectRuntime = coordinator.cardSelectRuntime(SeatId(1))
                    it.timeoutListener = { timedOut = true }
                }
            val result =
                prompt.requestCardSelect(
                    request(board, PromptSemantic.SelectNLegendRule, sourceId = null),
                    handles,
                )
            val requestMessage = coordinator.drain(SeatId(1)).flatten().single { it.hasSelectNReq() }
            val published = checkNotNull(publishedAtTimeout.get())

            assertSoftly {
                result.optionIndices shouldContainExactly listOf(0)
                (result.handles.single() === handles[0]) shouldBe true
                timedOut shouldBe true
                coordinator.cardSelect.current().shouldBeNull()
                requestMessage.selectNReq.idsCount shouldBe 2
                coordinator.cardSelect.submitSelectN(
                    published.interactionId,
                    published.gameStateId,
                    listOf(requestMessage.selectNReq.idsList[1]),
                ) shouldBe false
            }
        }
    })
