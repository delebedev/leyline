package leyline.game.mapping

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.BoardTag
import leyline.game.event.FrameEventLog
import leyline.game.mapping.StateMapper
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.BoardTestBase
import leyline.testkit.aiPlayer
import wotc.mtgo.gre.external.messaging.Messages
import wotc.mtgo.gre.external.messaging.Messages.ZoneType as ProtoZoneType

/**
 * Shape tests for [leyline.game.mapping.StateMapper] output — zone visibility, timers, player info.
 * Board-based (no game loop needed).
 */
class StateMapperShapeTest :
    FunSpec({

        tags(BoardTag)

        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("full state has timers") {
            val (b, game) = base.startWithBoard { _, _, _ -> }

            val snap = GsmSnapshot.capture(game, b, BoardTestBase.TEST_MATCH_ID, 1)
            val gs = StateMapper.buildFromSnapshot(snap, 1, BoardTestBase.TEST_MATCH_ID, b).gsm

            gs.timersCount shouldBeGreaterThanOrEqual 2
            val timer1 = gs.timersList.first { it.timerId == 1 }
            val timer2 = gs.timersList.first { it.timerId == 2 }
            assertSoftly {
                timer1.type shouldBe Messages.TimerType.Inactivity_a5e2
                timer2.type shouldBe Messages.TimerType.Inactivity_a5e2
                timer1.durationSec shouldBeGreaterThan 0
            }
        }

        test("zone visibility matches compatibility shape") {
            val (b, game) =
                base.startWithBoard { g, human, _ ->
                    base.addCard("Forest", human, ZoneType.Hand)
                    base.addCard("Forest", human, ZoneType.Graveyard)
                }

            val snap = GsmSnapshot.capture(game, b, BoardTestBase.TEST_MATCH_ID, 1)
            val gs = StateMapper.buildFromSnapshot(snap, 1, BoardTestBase.TEST_MATCH_ID, b).gsm

            val byId = gs.zonesList.associateBy { it.zoneId }
            assertSoftly {
                byId[ZoneIds.SUPPRESSED]!!.visibility shouldBe Messages.Visibility.Public
                byId[ZoneIds.PENDING]!!.visibility shouldBe Messages.Visibility.Public
                byId[ZoneIds.P1_SIDEBOARD]!!.visibility shouldBe Messages.Visibility.Private
                byId[ZoneIds.P2_SIDEBOARD]!!.visibility shouldBe Messages.Visibility.Private
            }

            val gyObjects =
                gs.gameObjectsList.filter { obj ->
                    obj.zoneId == ZoneIds.P1_GRAVEYARD || obj.zoneId == ZoneIds.P2_GRAVEYARD
                }
            for (obj in gyObjects) {
                obj.visibility shouldBe Messages.Visibility.Public
            }

            val handObjects =
                gs.gameObjectsList.filter { obj ->
                    obj.zoneId == ZoneIds.P1_HAND || obj.zoneId == ZoneIds.P2_HAND
                }
            for (obj in handObjects) {
                obj.visibility shouldBe Messages.Visibility.Private
            }
        }

        test("full state redacts opponent sideboard contents") {
            val (b, game) =
                base.startWithBoard { _, human, ai ->
                    base.addCard("Forest", human, ZoneType.Sideboard)
                    base.addCard("Mountain", ai, ZoneType.Sideboard)
                }

            val snap = GsmSnapshot.capture(game, b, BoardTestBase.TEST_MATCH_ID, 1)
            val gs = StateMapper.buildFromSnapshot(snap, 1, BoardTestBase.TEST_MATCH_ID, b, viewingSeatId = 1).gsm

            val byId = gs.zonesList.associateBy { it.zoneId }
            assertSoftly {
                byId[ZoneIds.P1_SIDEBOARD]!!.objectInstanceIdsCount shouldBe 1
                byId[ZoneIds.P2_SIDEBOARD]!!.objectInstanceIdsCount shouldBe 0
                gs.gameObjectsList.count { it.zoneId == ZoneIds.P1_SIDEBOARD } shouldBe 1
                gs.gameObjectsList.count { it.zoneId == ZoneIds.P2_SIDEBOARD } shouldBe 0
            }
        }

        test("diff state redacts changed opponent sideboard contents") {
            val (b, game) =
                base.startWithBoard { _, _, ai ->
                    base.addCard("Mountain", ai, ZoneType.Sideboard)
                }
            val prev = GsmSnapshot.capture(game, b, BoardTestBase.TEST_MATCH_ID, 1)
            base.addCard("Forest", game.aiPlayer, ZoneType.Sideboard)
            val cur = GsmSnapshot.capture(game, b, BoardTestBase.TEST_MATCH_ID, 2)

            val gs =
                StateMapper
                    .buildDiff(
                        prev = prev,
                        cur = cur,
                        events = FrameEventLog.EMPTY,
                        gameStateId = 2,
                        matchId = BoardTestBase.TEST_MATCH_ID,
                        bridge = b,
                        viewingSeatId = 1,
                    ).gsm
            val opponentSideboard = gs.zonesList.single { it.zoneId == ZoneIds.P2_SIDEBOARD }

            assertSoftly {
                opponentSideboard.objectInstanceIdsCount shouldBe 0
                gs.gameObjectsList.count { it.zoneId == ZoneIds.P2_SIDEBOARD } shouldBe 0
            }
        }

        test("buildFromSnapshot produces valid state") {
            val (b, game) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Hand)
                    base.addCard("Forest", human, ZoneType.Hand)
                    base.addCard("Llanowar Elves", human, ZoneType.Hand)
                }

            val snap = GsmSnapshot.capture(game, b, BoardTestBase.TEST_MATCH_ID, 1)
            val gs = StateMapper.buildFromSnapshot(snap, 1, BoardTestBase.TEST_MATCH_ID, b).gsm

            gs.zonesCount shouldBeGreaterThan 0
            gs.gameObjectsCount shouldBeGreaterThan 0

            val handZone = gs.zonesList.find { it.type == ProtoZoneType.Hand && it.ownerSeatId == 1 }
            handZone.shouldNotBeNull()
            handZone.objectInstanceIdsCount shouldBe 3

            gs.hasTurnInfo().shouldBeTrue()
        }

        test("game objects have card type fields") {
            val (b, game) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Forest", human, ZoneType.Hand)
                    base.addCard("Llanowar Elves", human, ZoneType.Hand)
                }

            val snap = GsmSnapshot.capture(game, b, BoardTestBase.TEST_MATCH_ID, 1)
            val gs = StateMapper.buildFromSnapshot(snap, 1, BoardTestBase.TEST_MATCH_ID, b).gsm

            val handZone = gs.zonesList.first { it.type == ProtoZoneType.Hand && it.ownerSeatId == 1 }
            val handInstanceIds = handZone.objectInstanceIdsList.toSet()
            val handObjects = gs.gameObjectsList.filter { it.instanceId in handInstanceIds }
            handObjects.shouldNotBeEmpty()

            for (obj in handObjects) {
                obj.cardTypesCount shouldBeGreaterThan 0
            }

            val lands =
                handObjects.filter {
                    it.cardTypesList.contains(Messages.CardType.Land_a80b)
                }
            lands.shouldNotBeEmpty()
            for (land in lands) {
                land.superTypesList shouldContain Messages.SuperType.Basic
                land.subtypesList shouldContain Messages.SubType.Forest
            }

            val creatures =
                handObjects.filter {
                    it.cardTypesList.contains(Messages.CardType.Creature)
                }
            creatures.shouldNotBeEmpty()
            for (c in creatures) {
                c.hasPower().shouldBeTrue()
                c.hasToughness().shouldBeTrue()
            }
        }

        test("player info has timer ids") {
            val (b, game) = base.startWithBoard { _, _, _ -> }

            val snap = GsmSnapshot.capture(game, b, BoardTestBase.TEST_MATCH_ID, 1)
            val gs = StateMapper.buildFromSnapshot(snap, 1, BoardTestBase.TEST_MATCH_ID, b).gsm

            for (player in gs.playersList) {
                player.timerIdsCount shouldBeGreaterThan 0
                player.timerIdsList[0] shouldBe player.systemSeatNumber
            }
        }
    })
