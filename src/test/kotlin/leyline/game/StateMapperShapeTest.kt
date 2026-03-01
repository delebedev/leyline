package leyline.game

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.conformance.ConformanceTestBase
import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages
import wotc.mtgo.gre.external.messaging.Messages.ZoneType as ProtoZoneType

/**
 * Shape tests for [StateMapper] output — zone visibility, timers, player info.
 * Board-based (no game loop needed).
 */
class StateMapperShapeTest :
    FunSpec({
        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("full state has timers") {
            val (b, game) = base.startWithBoard { _, _, _ -> }

            val gs = StateMapper.buildFromGame(game, 1, ConformanceTestBase.TEST_MATCH_ID, b)

            gs.timersCount shouldBeGreaterThanOrEqual 2
            val timer1 = gs.timersList.first { it.timerId == 1 }
            val timer2 = gs.timersList.first { it.timerId == 2 }
            timer1.type shouldBe Messages.TimerType.Inactivity_a5e2
            timer2.type shouldBe Messages.TimerType.Inactivity_a5e2
            timer1.durationSec shouldBeGreaterThan 0
        }

        test("zone visibility matches real client") {
            val (b, game) = base.startWithBoard { g, human, _ ->
                base.addCard("Forest", human, ZoneType.Hand)
                base.addCard("Forest", human, ZoneType.Graveyard)
            }

            val gs = StateMapper.buildFromGame(game, 1, ConformanceTestBase.TEST_MATCH_ID, b)

            val byId = gs.zonesList.associateBy { it.zoneId }
            byId[ZoneIds.SUPPRESSED]!!.visibility shouldBe Messages.Visibility.Public
            byId[ZoneIds.PENDING]!!.visibility shouldBe Messages.Visibility.Public
            byId[ZoneIds.P1_SIDEBOARD]!!.visibility shouldBe Messages.Visibility.Private
            byId[ZoneIds.P2_SIDEBOARD]!!.visibility shouldBe Messages.Visibility.Private

            val gyObjects = gs.gameObjectsList.filter { obj ->
                obj.zoneId == ZoneIds.P1_GRAVEYARD || obj.zoneId == ZoneIds.P2_GRAVEYARD
            }
            for (obj in gyObjects) {
                obj.visibility shouldBe Messages.Visibility.Public
            }

            val handObjects = gs.gameObjectsList.filter { obj ->
                obj.zoneId == ZoneIds.P1_HAND || obj.zoneId == ZoneIds.P2_HAND
            }
            for (obj in handObjects) {
                obj.visibility shouldBe Messages.Visibility.Private
            }
        }

        test("buildFromGame produces valid state") {
            val (b, game) = base.startWithBoard { _, human, _ ->
                base.addCard("Forest", human, ZoneType.Hand)
                base.addCard("Forest", human, ZoneType.Hand)
                base.addCard("Llanowar Elves", human, ZoneType.Hand)
            }

            val gs = StateMapper.buildFromGame(game, 1, ConformanceTestBase.TEST_MATCH_ID, b)

            gs.zonesCount shouldBeGreaterThan 0
            gs.gameObjectsCount shouldBeGreaterThan 0

            val handZone = gs.zonesList.find { it.type == ProtoZoneType.Hand && it.ownerSeatId == 1 }
            handZone.shouldNotBeNull()
            handZone.objectInstanceIdsCount shouldBe 3

            gs.hasTurnInfo().shouldBeTrue()
        }

        test("game objects have card type fields") {
            val (b, game) = base.startWithBoard { _, human, _ ->
                base.addCard("Forest", human, ZoneType.Hand)
                base.addCard("Llanowar Elves", human, ZoneType.Hand)
            }

            val gs = StateMapper.buildFromGame(game, 1, ConformanceTestBase.TEST_MATCH_ID, b)

            val handZone = gs.zonesList.first { it.type == ProtoZoneType.Hand && it.ownerSeatId == 1 }
            val handInstanceIds = handZone.objectInstanceIdsList.toSet()
            val handObjects = gs.gameObjectsList.filter { it.instanceId in handInstanceIds }
            handObjects.shouldNotBeEmpty()

            for (obj in handObjects) {
                obj.cardTypesCount shouldBeGreaterThan 0
            }

            val lands = handObjects.filter {
                it.cardTypesList.contains(Messages.CardType.Land_a80b)
            }
            lands.shouldNotBeEmpty()
            for (land in lands) {
                land.superTypesList.contains(Messages.SuperType.Basic).shouldBeTrue()
                land.subtypesList.contains(Messages.SubType.Forest).shouldBeTrue()
            }

            val creatures = handObjects.filter {
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

            val gs = StateMapper.buildFromGame(game, 1, ConformanceTestBase.TEST_MATCH_ID, b)

            for (player in gs.playersList) {
                player.timerIdsCount shouldBeGreaterThan 0
                player.timerIdsList[0] shouldBe player.systemSeatNumber
            }
        }
    })
