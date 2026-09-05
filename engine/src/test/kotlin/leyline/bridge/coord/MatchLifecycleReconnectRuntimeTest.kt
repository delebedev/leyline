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
import leyline.testkit.BoardTest
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

class MatchLifecycleReconnectRuntimeTest :
    BoardTest({
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
