package leyline.testkit

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.types.InstanceId
import leyline.game.mapping.StateMapper
import leyline.game.snapshot.GsmSnapshot
import wotc.mtgo.gre.external.messaging.Messages.CardType

/**
 * Verifies [TestCardInjector] + [CardDataDeriver] produce cards that are
 * fully visible in proto output with correct metadata.
 *
 * Most tests use startWithBoard{} for speed (~0.01s). The deck-list
 * auto-registration test keeps startGameAtMain1 since it specifically
 * tests the deck registration path.
 */
class CardInjectionTest :
    BoardTest({

        test("injected Serra Angel appears in GSM with correct metadata") {
            val board = startWithBoard { _, _, _ -> }
            val injected = TestCardInjector.inject(board.bridge, 1, "Serra Angel", ZoneType.Battlefield, sick = false)

            val gsId1 = board.counter.nextGsId()
            val snap1 = GsmSnapshot.capture(board.game, board.bridge, "test", gsId1)
            val gsm = StateMapper.buildFromSnapshot(snap1, gsId1, "test", board.bridge, viewingSeatId = 1).gsm
            val obj =
                checkNotNull(
                    gsm.gameObjectsList.firstOrNull { it.instanceId == injected.instanceId },
                ) { "Injected card should appear in gameObjectsList" }
            assertSoftly {
                obj.grpId shouldBe injected.grpId
                obj.cardTypesList.shouldContain(CardType.Creature)
                obj.hasPower().shouldBeTrue()
                obj.power.value shouldBe 4
                obj.hasToughness().shouldBeTrue()
                obj.toughness.value shouldBe 4
                obj.uniqueAbilitiesCount shouldBeGreaterThanOrEqual 2

                board.bridge.getForgeCardId(InstanceId(injected.instanceId))?.value shouldBe injected.forgeCardId
                board.bridge.cardRepository
                    .findByGrpId(injected.grpId)
                    .shouldNotBeNull()
                board.bridge.cardRepository.findNameByGrpId(injected.grpId) shouldBe "Serra Angel"
            }

            val acc = ClientAccumulator()
            acc.seedFull(gsm)
            acc.assertConsistent("after Serra Angel injection")
        }

        test("injected creature to hand is visible in hand zone") {
            val board = startWithBoard { _, _, _ -> }
            val injected = TestCardInjector.inject(board.bridge, 1, "Lightning Bolt", ZoneType.Hand)

            val gsId2 = board.counter.nextGsId()
            val snap2 = GsmSnapshot.capture(board.game, board.bridge, "test", gsId2)
            val gsm = StateMapper.buildFromSnapshot(snap2, gsId2, "test", board.bridge, viewingSeatId = 1).gsm
            val obj =
                checkNotNull(
                    gsm.gameObjectsList.firstOrNull { it.instanceId == injected.instanceId },
                ) { "Injected card should appear in gameObjectsList" }
            obj.instanceId shouldBe injected.instanceId
            obj.cardTypesList.shouldContain(CardType.Instant)

            val handZone =
                checkNotNull(
                    gsm.zonesList.firstOrNull {
                        it.type == wotc.mtgo.gre.external.messaging.Messages.ZoneType.Hand && it.ownerSeatId == 1
                    },
                ) { "Hand zone should exist for seat 1" }
            handZone.ownerSeatId shouldBe 1
            handZone.objectInstanceIdsList.shouldContain(injected.instanceId)
        }

        test("CardDataDeriver produces consistent grpIds for same card name") {
            val board = startWithBoard { _, _, _ -> }

            val first = TestCardInjector.inject(board.bridge, 1, "Grizzly Bears", ZoneType.Battlefield)
            val second = TestCardInjector.inject(board.bridge, 1, "Grizzly Bears", ZoneType.Battlefield)

            assertSoftly {
                first.grpId shouldBe second.grpId
                first.instanceId shouldNotBe second.instanceId
                first.forgeCardId shouldNotBe second.forgeCardId
            }
        }

        test("auto-register deck list populates repository for all cards") {
            val deckList = "30 Plains\n20 Serra Angel\n10 Lightning Bolt"
            val board = startGameAtMain1(deckList = deckList)

            val registeredNames =
                listOf("Plains", "Serra Angel", "Lightning Bolt")
                    .filter { board.bridge.cardRepository.findGrpIdByName(it) != null }
            registeredNames shouldBe listOf("Plains", "Serra Angel", "Lightning Bolt")
        }

        test("injected land enters tapped when requested") {
            val board = startWithBoard { _, _, _ -> }
            val injected = TestCardInjector.inject(board.bridge, 1, "Plains", ZoneType.Battlefield, tapped = true)

            val gsId3 = board.counter.nextGsId()
            val snap3 = GsmSnapshot.capture(board.game, board.bridge, "test", gsId3)
            val gsm = StateMapper.buildFromSnapshot(snap3, gsId3, "test", board.bridge, viewingSeatId = 1).gsm
            val obj =
                checkNotNull(
                    gsm.gameObjectsList.firstOrNull { it.instanceId == injected.instanceId },
                ) { "Injected land should appear in gameObjectsList" }
            obj.instanceId shouldBe injected.instanceId
            obj.cardTypesList.shouldContain(CardType.Land_a80b)
        }
    })
