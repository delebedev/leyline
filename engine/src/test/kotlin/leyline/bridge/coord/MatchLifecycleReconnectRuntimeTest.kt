package leyline.bridge.coord

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.types.SeatId
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.mapping.ZoneIds
import leyline.game.state.GameBridge
import leyline.game.state.ProjectionViewer
import leyline.game.state.ProjectionViewerRole
import leyline.match.ResponseEnvelopeGuard
import leyline.testkit.BoardTest
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.FailureReason
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.MulliganOption
import wotc.mtgo.gre.external.messaging.Messages.MulliganResp
import java.util.concurrent.atomic.AtomicReference

@Suppress("NoThreadSleepInTests")
private fun awaitMulliganPrompt(
    bridge: GameBridge,
    seatId: SeatId,
): leyline.bridge.handoff.MulliganBridge.PendingPrompt {
    repeat(100) {
        bridge.mulliganBridge(seatId).pendingPrompt()?.let { return it }
        Thread.sleep(10)
    }
    error("Mulligan prompt was not published")
}

class MatchLifecycleReconnectRuntimeTest :
    BoardTest({
        test("reconnect republishes the current keep prompt with fresh correlation") {
            val board = startWithBoard { _, human, _ -> repeat(7) { addCard("Forest", human, ZoneType.Hand) } }
            val bridge = board.bridge
            val coordinator = bridge.cutCoordinator
            val seatId = bridge.seating.humanSeat
            coordinator.registerViewer(seatId)
            coordinator.lifecycle.publishInitial(seatId, includeStartingPlayerPrompt = true)
            coordinator.drain(seatId)
            coordinator.lifecycle.publishDealHand(seatId)
            coordinator.drain(seatId)

            val decision = AtomicReference<Boolean>()
            val engineThread =
                Thread {
                    decision.set(bridge.mulliganBridge(seatId).awaitKeepDecision(playerId = 0, mulliganCount = 2))
                }.apply {
                    isDaemon = true
                    start()
                }
            val prompt = awaitMulliganPrompt(bridge, seatId)
            coordinator.lifecycle.publishMulliganRequest(seatId, mulliganCount = 0, numCards = 7)
            val staleRequest =
                coordinator
                    .feed(seatId)
                    .queue
                    .single()
                    .messages
                    .single { it.hasMulliganReq() }

            val reconnectGameStateId = coordinator.lifecycle.publishInitial(seatId, includeStartingPlayerPrompt = true)
            val reconnect = coordinator.drain(seatId).single()
            val fullState = reconnect.single { it.hasGameStateMessage() }.gameStateMessage
            val request = reconnect.single { it.hasMulliganReq() }
            val staleResponse = mulliganResponse(staleRequest.gameStateId, staleRequest.msgId)
            val currentResponse = mulliganResponse(reconnectGameStateId, request.msgId)

            assertSoftly {
                reconnect.map { it.type } shouldBe
                    listOf(
                        wotc.mtgo.gre.external.messaging.Messages.GREMessageType.ConnectResp_695e,
                        wotc.mtgo.gre.external.messaging.Messages.GREMessageType.GameStateMessage_695e,
                        wotc.mtgo.gre.external.messaging.Messages.GREMessageType.MulliganReq_aa0d,
                    )
                fullState.pendingMessageCount shouldBe 1
                request.gameStateId shouldBe reconnectGameStateId
                request.msgId shouldNotBe staleRequest.msgId
                reconnect.none { it.msgId == staleRequest.msgId } shouldBe true
                prompt.mulliganCount shouldBe 2
                request.mulliganReq.mulliganCount shouldBe 0
                request.prompt.parametersList
                    .single { it.parameterName == "NumberOfCards" }
                    .numberValue shouldBe 7
                ResponseEnvelopeGuard.mismatchReason(staleResponse, bridge.committedSequence(), bridge.responseAcceptance) shouldBe
                    FailureReason.ReqRespMismatch
                ResponseEnvelopeGuard.mismatchReason(currentResponse, bridge.committedSequence(), bridge.responseAcceptance) shouldBe null
                bridge.submitKeep(seatId) shouldBe true
            }
            engineThread.join(2_000)
            decision.get() shouldBe true
        }

        test("reconnect republishes the current London tuck prompt with fresh correlation") {
            val board = startWithBoard { _, human, _ -> repeat(7) { addCard("Forest", human, ZoneType.Hand) } }
            val bridge = board.bridge
            val coordinator = bridge.cutCoordinator
            val seatId = bridge.seating.humanSeat
            coordinator.registerViewer(seatId)
            coordinator.lifecycle.publishInitial(seatId, includeStartingPlayerPrompt = true)
            coordinator.drain(seatId)
            coordinator.lifecycle.publishDealHand(seatId)
            coordinator.drain(seatId)
            coordinator.lifecycle.publishMulliganRequest(seatId, mulliganCount = 1, numCards = 7)
            val staleRequest = coordinator.drain(seatId).single().single { it.hasMulliganReq() }

            val tucked = AtomicReference<List<forge.game.card.Card>>()
            val engineThread =
                Thread {
                    tucked.set(
                        bridge
                            .mulliganBridge(seatId)
                            .awaitTuckDecision(playerId = 0, count = 1, hand = board.human.getZone(ZoneType.Hand).cards),
                    )
                }.apply {
                    isDaemon = true
                    start()
                }
            awaitMulliganPrompt(bridge, seatId)

            val reconnectGameStateId = coordinator.lifecycle.publishInitial(seatId, includeStartingPlayerPrompt = true)
            val reconnect = coordinator.drain(seatId).single()
            val fullState = reconnect.single { it.hasGameStateMessage() }.gameStateMessage
            val request = reconnect.single { it.hasGroupReq() }
            val staleResponse = groupResponse(staleRequest.gameStateId, staleRequest.msgId)
            val currentResponse = groupResponse(reconnectGameStateId, request.msgId)

            assertSoftly {
                fullState.pendingMessageCount shouldBe 1
                request.gameStateId shouldBe reconnectGameStateId
                request.groupReq.instanceIdsList shouldBe
                    fullState.zonesList.single { it.zoneId == ZoneIds.handOf(seatId) }.objectInstanceIdsList
                request.groupReq.groupSpecsList[1].lowerBound shouldBe 1
                ResponseEnvelopeGuard.mismatchReason(staleResponse, bridge.committedSequence(), bridge.responseAcceptance) shouldBe
                    FailureReason.ReqRespMismatch
                ResponseEnvelopeGuard.mismatchReason(currentResponse, bridge.committedSequence(), bridge.responseAcceptance) shouldBe null
            }
            bridge.mulliganBridge(seatId).submitTuck(emptyList())
            engineThread.join(2_000)
            tucked.get() shouldBe emptyList()
        }

        test("reconnect after redraw keeps only the current hand identities") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Hand)
                    addCard("Mountain", human, ZoneType.Hand)
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val coordinator = board.bridge.cutCoordinator
            coordinator.registerViewer(SeatId(1))
            coordinator.lifecycle.publishInitial(SeatId(1), includeStartingPlayerPrompt = true)
            coordinator.drain(SeatId(1))
            coordinator.lifecycle.publishFullState(SeatId(1))
            coordinator.drain(SeatId(1))
            val priorIds =
                board.bridge
                    .projectionStateSnapshot()
                    .identities.forgeIdToInstanceId.values
                    .map { it.value }
            val preservedBattlefieldId =
                checkNotNull(
                    board.bridge
                        .projectionStateSnapshot()
                        .viewerCursors
                        .getValue(SeatId(1))
                        .fullState,
                ).zonesList
                    .single { it.zoneId == ZoneIds.BATTLEFIELD }
                    .objectInstanceIdsList
                    .single()

            val redrawGameStateId = coordinator.lifecycle.publishMulliganRedraw(SeatId(1), MulliganRedrawFacts(0, 2))
            val redraw =
                coordinator
                    .drain(SeatId(1))
                    .single()
                    .first()
                    .gameStateMessage
            val currentHand = redraw.zonesList.single { it.zoneId == ZoneIds.P1_HAND }.objectInstanceIdsList
            val retiredIds = redraw.diffDeletedInstanceIdsList
            val redrawCursor =
                board.bridge
                    .projectionStateSnapshot()
                    .viewerCursors
                    .getValue(SeatId(1))
            val redrawFull = checkNotNull(redrawCursor.fullState)
            val retainedHand =
                redrawFull.zonesList
                    .single { it.zoneId == ZoneIds.P1_HAND }
                    .objectInstanceIdsList

            coordinator.lifecycle.publishInitial(SeatId(1), includeStartingPlayerPrompt = true)
            val reconnect =
                coordinator
                    .drain(SeatId(1))
                    .single()
                    .single { it.hasGameStateMessage() }
                    .gameStateMessage
            val reconnectHand = reconnect.zonesList.single { it.zoneId == ZoneIds.P1_HAND }.objectInstanceIdsList
            val reconnectObjects = reconnect.gameObjectsList.map { it.instanceId }

            assertSoftly {
                retiredIds.toSet() shouldBe priorIds.toSet() - preservedBattlefieldId
                currentHand.size shouldBe 2
                currentHand.none(retiredIds::contains) shouldBe true
                redrawCursor.previousSnapshot?.gameStateId shouldBe redrawGameStateId
                redrawFull.gameStateId shouldBe redrawGameStateId
                retainedHand shouldBe currentHand
                reconnectHand shouldBe currentHand
                reconnectObjects.toSet() shouldBe currentHand.toSet() + preservedBattlefieldId
                reconnectObjects.none(retiredIds::contains) shouldBe true
                reconnect.zonesList.flatMap { it.objectInstanceIdsList }.none(retiredIds::contains) shouldBe true
            }
        }

        test("brawl reconnect after redraw preserves commander and sideboard identities") {
            val deck =
                """
                [Commander]
                1 Isamaru, Hound of Konda
                [Deck]
                25 Plains
                33 Savannah Lions
                [Sideboard]
                1 Environmental Sciences
                """.trimIndent()
            TestCardRegistry.ensureDeckRegistered(deck)
            val bridge =
                GameBridge(
                    initialSequence = LogicalSequencePlanner(initialGsId = 20, initialMsgId = 0).snapshot(),
                    cardRepository = TestCardRegistry.repo,
                )
            useBridge(bridge)
            bridge.start(seed = 42L, deckList = deck, variant = "brawl")
            val coordinator = bridge.cutCoordinator

            coordinator.lifecycle.publishInitial(SeatId(1), includeStartingPlayerPrompt = true)
            val initial =
                coordinator
                    .drain(SeatId(1))
                    .single()
                    .single { it.hasGameStateMessage() }
                    .gameStateMessage
            val commanderIds = initial.zonesList.single { it.zoneId == ZoneIds.COMMAND }.objectInstanceIdsList
            val sideboardIds = initial.zonesList.single { it.zoneId == ZoneIds.P1_SIDEBOARD }.objectInstanceIdsList

            coordinator.lifecycle.publishMulliganRedraw(SeatId(1), MulliganRedrawFacts(0, 7))
            val redraw =
                coordinator
                    .drain(SeatId(1))
                    .single()
                    .first()
                    .gameStateMessage
            val retainedIds =
                bridge
                    .projectionStateSnapshot()
                    .identities.instanceIdToForgeId.keys
                    .map { it.value }

            coordinator.lifecycle.publishInitial(SeatId(1), includeStartingPlayerPrompt = true)
            val reconnect =
                coordinator
                    .drain(SeatId(1))
                    .single()
                    .single { it.hasGameStateMessage() }
                    .gameStateMessage
            val retiredIds = redraw.diffDeletedInstanceIdsList

            assertSoftly {
                commanderIds.size shouldBe 2
                sideboardIds.size shouldBe 1
                commanderIds.all(retainedIds::contains) shouldBe true
                sideboardIds.all(retainedIds::contains) shouldBe true
                reconnect.zonesList.single { it.zoneId == ZoneIds.COMMAND }.objectInstanceIdsList shouldBe commanderIds
                reconnect.zonesList.single { it.zoneId == ZoneIds.P1_SIDEBOARD }.objectInstanceIdsList shouldBe sideboardIds
                reconnect.gameObjectsList.map { it.instanceId }.containsAll(commanderIds) shouldBe true
                reconnect.zonesList.flatMap { it.objectInstanceIdsList }.none(retiredIds::contains) shouldBe true
                reconnect.gameObjectsList.none { it.instanceId in retiredIds } shouldBe true
            }
        }

        test("redraw retires linked face identities with their parent") {
            val board = startWithBoard { _, human, _ -> addCard("Lunarch Veteran", human, ZoneType.Hand) }
            val coordinator = board.bridge.cutCoordinator
            coordinator.registerViewer(SeatId(1))
            coordinator.lifecycle.publishInitial(SeatId(1), includeStartingPlayerPrompt = true)
            coordinator.drain(SeatId(1))
            coordinator.lifecycle.publishDealHand(SeatId(1))
            val deal =
                coordinator
                    .drain(SeatId(1))
                    .single()
                    .single()
                    .gameStateMessage
            val oldParentId = deal.gameObjectsList.single { it.type == GameObjectType.Card }.instanceId
            val oldBackId = deal.gameObjectsList.single { it.type == GameObjectType.DisturbBack }.instanceId

            coordinator.lifecycle.publishMulliganRedraw(SeatId(1), MulliganRedrawFacts(0, 1))
            val redraw =
                coordinator
                    .drain(SeatId(1))
                    .single()
                    .first()
                    .gameStateMessage
            val newParentId = redraw.gameObjectsList.single { it.type == GameObjectType.Card }.instanceId
            val newBack = redraw.gameObjectsList.single { it.type == GameObjectType.DisturbBack }

            coordinator.lifecycle.publishInitial(SeatId(1), includeStartingPlayerPrompt = true)
            val reconnect =
                coordinator
                    .drain(SeatId(1))
                    .single()
                    .single { it.hasGameStateMessage() }
                    .gameStateMessage
            val reconnectBack = reconnect.gameObjectsList.single { it.type == GameObjectType.DisturbBack }
            val retainedRegistryIds =
                board.bridge
                    .projectionStateSnapshot()
                    .identities.instanceIdToForgeId.keys

            assertSoftly {
                redraw.diffDeletedInstanceIdsList.toSet() shouldBe setOf(oldParentId, oldBackId)
                newParentId shouldNotBe oldParentId
                newBack.instanceId shouldNotBe oldBackId
                newBack.parentId shouldBe newParentId
                reconnect.gameObjectsList.none { it.instanceId == oldParentId || it.instanceId == oldBackId } shouldBe true
                reconnectBack.instanceId shouldBe newBack.instanceId
                reconnectBack.parentId shouldBe newParentId
                retainedRegistryIds.none {
                    it.value == oldParentId || it.value == oldBackId
                } shouldBe true
            }
        }

        test("redraw rebases every viewer cursor without publishing an observer batch") {
            val board = startWithBoard { _, human, _ -> addCard("Forest", human, ZoneType.Hand) }
            val coordinator = board.bridge.cutCoordinator
            coordinator.registerViewers(
                listOf(
                    ProjectionViewer(SeatId(1), ProjectionViewerRole.Player),
                    ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer),
                ),
            )
            coordinator.lifecycle.publishInitial(SeatId(1), includeStartingPlayerPrompt = true)
            coordinator.drain(SeatId(1))
            coordinator.drain(SeatId(2))

            coordinator.lifecycle.publishMulliganRedraw(SeatId(1), MulliganRedrawFacts(0, 1))
            val redraw =
                coordinator
                    .drain(SeatId(1))
                    .single()
                    .first()
                    .gameStateMessage
            val currentHandIds = redraw.zonesList.single { it.zoneId == ZoneIds.P1_HAND }.objectInstanceIdsList
            val retiredIds = redraw.diffDeletedInstanceIdsList
            val cursors = board.bridge.projectionStateSnapshot().viewerCursors
            val playerFull = checkNotNull(cursors.getValue(SeatId(1)).fullState)
            val observerFull = checkNotNull(cursors.getValue(SeatId(2)).fullState)
            val observerRedrawBatches = coordinator.drain(SeatId(2))

            coordinator.lifecycle.publishInitial(SeatId(1), includeStartingPlayerPrompt = true)
            val playerReconnect =
                coordinator
                    .drain(SeatId(1))
                    .single()
                    .single { it.hasGameStateMessage() }
                    .gameStateMessage
            coordinator.lifecycle.publishInitial(SeatId(2), includeStartingPlayerPrompt = true)
            val observerReconnect =
                coordinator
                    .drain(SeatId(2))
                    .single()
                    .single { it.hasGameStateMessage() }
                    .gameStateMessage

            assertSoftly {
                observerRedrawBatches.shouldBeEmpty()
                listOf(playerFull, observerFull)
                    .map { it.gameStateId }
                    .distinct()
                    .size shouldBe 1
                cursors.values.forEach { cursor ->
                    val fullState = checkNotNull(cursor.fullState)
                    fullState.zonesList.single { it.zoneId == ZoneIds.P1_HAND }.objectInstanceIdsList shouldBe currentHandIds
                    fullState.gameObjectsList.none { it.instanceId in retiredIds } shouldBe true
                }
                playerFull.gameObjectsList.map { it.instanceId } shouldBe currentHandIds
                observerFull.gameObjectsCount shouldBe 0
                playerReconnect.zonesList.single { it.zoneId == ZoneIds.P1_HAND }.objectInstanceIdsList shouldBe currentHandIds
                observerReconnect.zonesList.single { it.zoneId == ZoneIds.P1_HAND }.objectInstanceIdsList shouldBe currentHandIds
                observerReconnect.gameObjectsCount shouldBe 0
                listOf(playerReconnect, observerReconnect).flatMap { it.gameObjectsList }.none {
                    it.instanceId in retiredIds
                } shouldBe true
            }
        }
    })

private fun mulliganResponse(
    gameStateId: Int,
    respId: Int,
): ClientToGREMessage =
    ClientToGREMessage
        .newBuilder()
        .setType(ClientMessageType.MulliganResp_097b)
        .setGameStateId(gameStateId)
        .setRespId(respId)
        .setMulliganResp(MulliganResp.newBuilder().setDecision(MulliganOption.AcceptHand))
        .build()

private fun groupResponse(
    gameStateId: Int,
    respId: Int,
): ClientToGREMessage =
    ClientToGREMessage
        .newBuilder()
        .setType(ClientMessageType.GroupResp_097b)
        .setGameStateId(gameStateId)
        .setRespId(respId)
        .build()
