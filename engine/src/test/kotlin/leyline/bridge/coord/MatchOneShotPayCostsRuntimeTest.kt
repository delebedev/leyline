package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PayCostsPromptRoute
import leyline.bridge.handoff.PayCostsRouteKind
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.EffectCostType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchOneShotPayCostsRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:one shot PayCosts runtime
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Savannah Lions;Coral Merfolk;Forest
            humanlibrary=Mountain
            ailibrary=Forest
            """.trimIndent()

        fun candidates(board: Board): List<Card> =
            board.human
                .getZone(ZoneType.Battlefield)
                .cards
                .filter { it.name != "Forest" }

        fun request(
            cards: List<Card>,
            kind: PayCostsRouteKind,
            sourceId: Int,
        ): PromptRequest {
            val weighted = kind == PayCostsRouteKind.CollectEvidence || kind == PayCostsRouteKind.TeamworkCost
            return PromptRequest(
                promptType = "choose_cards",
                message = "Pay cost",
                options = cards.map { it.name },
                min = if (weighted) 0 else 1,
                max = if (weighted) cards.size else 1,
                candidateRefs =
                    cards.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Battlefield.name)
                    },
                route = ResolvedPromptRoute.PayCosts(PayCostsPromptRoute(PromptSemantic.SelectNCostSacrifice, kind, kind.name)),
                sourceEntityId = sourceId,
                costSelectionWeights = if (weighted) listOf(2, 1) else emptyList(),
                minSelectionWeight = if (weighted) 2 else null,
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

        test("all seven routes publish one atomic state and PayCosts cut and resolve exact option") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val cards = candidates(board)
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Forest" }
            val routes =
                listOf(
                    PayCostsRouteKind.Sacrifice to PromptIds.CHOOSE_OR_COST_PAY_SACRIFICE,
                    PayCostsRouteKind.SelectCostExileFromGrave to PromptIds.CHOOSE_OR_COST_PAY_EXILE_FROM_GRAVE,
                    PayCostsRouteKind.SelectCostReturnAttacker to PromptIds.NINJUTSU_RETURN_UNBLOCKED_ATTACKER_COST,
                    PayCostsRouteKind.CollectEvidence to PromptIds.COLLECT_EVIDENCE_COST,
                    PayCostsRouteKind.StationTapCost to PromptIds.STATION_TAP_COST,
                    PayCostsRouteKind.EnlistCost to PromptIds.ENLIST_TAP_COST,
                    PayCostsRouteKind.TeamworkCost to PromptIds.TEAMWORK_TAP_COST,
                )

            routes.forEach { (kind, promptId) ->
                val result = AtomicReference<leyline.bridge.handoff.OneShotPayCostsResult>()
                val finished = CountDownLatch(1)
                Thread {
                    result.set(coordinator.oneShotPayCostsRuntime(SeatId(1)).awaitPayment(request(cards, kind, source.id), cards, 3_000))
                    finished.countDown()
                }.start()

                val published = awaitPublished(coordinator)
                val batch = coordinator.drain(SeatId(1)).single()
                val message = batch.single { it.hasPayCostsReq() }
                val payCosts = message.payCostsReq
                val selection = payCosts.effectCostReq.costSelection
                val selected =
                    selection.idsList
                        .first()
                val sourceInstanceId =
                    board.bridge
                        .projectionStateSnapshot()
                        .identities.forgeIdToInstanceId
                        .getValue(ForgeCardId(source.id))
                        .value
                assertSoftly {
                    batch.map { it.type } shouldContainExactly
                        listOf(GREMessageType.GameStateMessage_695e, GREMessageType.PayCostsReq_695e)
                    message.prompt.promptId shouldBe promptId
                    message.gameStateId shouldBe published.gameStateId
                    message.allowCancel shouldBe AllowCancel.Abort
                    message.allowUndo shouldBe true
                    message.prompt.parametersList
                        .single { it.parameterName == "CardId" }
                        .numberValue shouldBe sourceInstanceId
                    payCosts.hasPaymentActions() shouldBe true
                    payCosts.effectCostReq.effectCostType shouldBe EffectCostType.Select_a59c
                    selection.context shouldBe SelectionContext.NonManaPayment
                    selection.optionContext shouldBe OptionContext.Payment
                    selection.listType shouldBe SelectionListType.Dynamic
                    selection.idType shouldBe IdType.InstanceId_ab2c
                    selection.validationType shouldBe SelectionValidationType.NonRepeatable
                    selection.idsCount shouldBe cards.size
                    selection.maxWeight shouldBe Int.MAX_VALUE
                    finished.count shouldBe 1
                }
                when (kind) {
                    PayCostsRouteKind.CollectEvidence ->
                        assertSoftly {
                            selection.minSel shouldBe 0
                            selection.maxSel shouldBe cards.size
                            selection.minWeight shouldBe 2
                            selection.weightsList shouldContainExactly listOf(2, 1)
                        }
                    PayCostsRouteKind.TeamworkCost ->
                        assertSoftly {
                            selection.minSel shouldBe 2
                            selection.maxSel shouldBe Int.MAX_VALUE
                            selection.minWeight shouldBe Int.MIN_VALUE
                            selection.weightsList shouldContainExactly listOf(2, 1)
                        }
                    PayCostsRouteKind.SelectCostExileFromGrave ->
                        assertSoftly {
                            message.prompt.promptId shouldBe 5500
                            selection.minSel shouldBe 1
                            selection.maxSel shouldBe 1
                            selection.minWeight shouldBe Int.MIN_VALUE
                            selection.weightsList shouldContainExactly listOf(1, 1)
                        }
                    PayCostsRouteKind.Sacrifice,
                    PayCostsRouteKind.SelectCostReturnAttacker,
                    PayCostsRouteKind.StationTapCost,
                    PayCostsRouteKind.EnlistCost,
                    ->
                        assertSoftly {
                            selection.minSel shouldBe 1
                            selection.maxSel shouldBe 1
                            selection.minWeight shouldBe Int.MIN_VALUE
                            selection.weightsList shouldContainExactly listOf(1, 1)
                        }
                    PayCostsRouteKind.ConvokeCost,
                    PayCostsRouteKind.ImproviseCost,
                    PayCostsRouteKind.WaterbendCost,
                    -> error("Iterative route entered one-shot materializer proof")
                }
                val accepted = coordinator.oneShotPayCosts.submit(published.interactionId, published.gameStateId, listOf(selected))
                assertSoftly {
                    accepted shouldBe true
                    finished.await(3, TimeUnit.SECONDS) shouldBe true
                    result.get().optionIndices shouldContainExactly listOf(0)
                    (result.get().handles.single() === cards.first()) shouldBe true
                }
            }
        }

        test("stale duplicate cardinality and weight-invalid responses leave the exact window pending") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val cards = candidates(board)
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Forest" }

            fun launch(request: PromptRequest): Pair<AtomicReference<leyline.bridge.handoff.OneShotPayCostsResult>, CountDownLatch> {
                val result = AtomicReference<leyline.bridge.handoff.OneShotPayCostsResult>()
                val finished = CountDownLatch(1)
                Thread {
                    result.set(coordinator.oneShotPayCostsRuntime(SeatId(1)).awaitPayment(request, cards, 3_000))
                    finished.countDown()
                }.start()
                return result to finished
            }

            val (exactResult, exactFinished) = launch(request(cards, PayCostsRouteKind.Sacrifice, source.id))
            val exact = awaitPublished(coordinator)
            val exactIds =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasPayCostsReq() }
                    .payCostsReq.effectCostReq.costSelection.idsList
            assertSoftly {
                coordinator.oneShotPayCosts.submit("stale", exact.gameStateId, listOf(exactIds.first())) shouldBe false
                coordinator.oneShotPayCosts.submit(exact.interactionId, exact.gameStateId + 1, listOf(exactIds.first())) shouldBe false
                coordinator.oneShotPayCosts.submit(exact.interactionId, exact.gameStateId, listOf(Int.MAX_VALUE)) shouldBe false
                coordinator.oneShotPayCosts.submit(
                    exact.interactionId,
                    exact.gameStateId,
                    listOf(exactIds.first(), exactIds.first()),
                ) shouldBe
                    false
                coordinator.oneShotPayCosts.submit(exact.interactionId, exact.gameStateId, emptyList()) shouldBe false
                coordinator.oneShotPayCosts.submit(exact.interactionId, exact.gameStateId, exactIds) shouldBe false
                coordinator.oneShotPayCosts.current() shouldBe exact
                coordinator.oneShotPayCosts.submit(exact.interactionId, exact.gameStateId, listOf(exactIds.first())) shouldBe true
                exactFinished.await(3, TimeUnit.SECONDS) shouldBe true
                exactResult.get().optionIndices shouldContainExactly listOf(0)
                coordinator.oneShotPayCosts.submit(exact.interactionId, exact.gameStateId, listOf(exactIds.first())) shouldBe false
            }

            val (weightedResult, weightedFinished) = launch(request(cards, PayCostsRouteKind.CollectEvidence, source.id))
            val weighted = awaitPublished(coordinator)
            val weightedIds =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasPayCostsReq() }
                    .payCostsReq.effectCostReq.costSelection.idsList
            assertSoftly {
                coordinator.oneShotPayCosts.submit(weighted.interactionId, weighted.gameStateId, listOf(weightedIds.last())) shouldBe false
                coordinator.oneShotPayCosts.current() shouldBe weighted
                coordinator.oneShotPayCosts.submit(weighted.interactionId, weighted.gameStateId, listOf(weightedIds.first())) shouldBe true
                weightedFinished.await(3, TimeUnit.SECONDS) shouldBe true
                weightedResult.get().optionIndices shouldContainExactly listOf(0)
            }

            val (cancelResult, cancelFinished) = launch(request(cards, PayCostsRouteKind.EnlistCost, source.id))
            val cancellable = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            coordinator.oneShotPayCosts.cancel(cancellable.interactionId, cancellable.gameStateId) shouldBe true
            assertSoftly {
                cancelFinished.await(3, TimeUnit.SECONDS) shouldBe true
                cancelResult.get().optionIndices shouldBe emptyList()
                cancelResult.get().handles shouldBe emptyList()
            }
        }

        test("bridge timeout returns the configured default handle and requests later progression") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val cards = candidates(board)
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Forest" }
            val autoAdvance = CountDownLatch(1)
            val result = AtomicReference<leyline.bridge.handoff.OneShotPayCostsResult>()
            val finished = CountDownLatch(1)
            val bridge =
                InteractivePromptBridge(timeoutMs = 25).also {
                    it.oneShotPayCostsRuntime = coordinator.oneShotPayCostsRuntime(SeatId(1))
                    it.timeoutListener = autoAdvance::countDown
                }
            Thread {
                result.set(
                    bridge.requestOneShotPayCosts(
                        request(cards, PayCostsRouteKind.Sacrifice, source.id).copy(defaultIndex = 1),
                        cards,
                    ),
                )
                finished.countDown()
            }.start()
            awaitPublished(coordinator)
            coordinator.drain(SeatId(1))

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().optionIndices shouldContainExactly listOf(1)
                (result.get().handles.single() === cards[1]) shouldBe true
                autoAdvance.await(3, TimeUnit.SECONDS) shouldBe true
                coordinator.oneShotPayCosts.current().shouldBeNull()
                coordinator.failure().shouldBeNull()
            }
        }
    })
