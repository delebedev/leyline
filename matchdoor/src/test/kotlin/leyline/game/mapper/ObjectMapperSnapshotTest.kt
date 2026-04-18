package leyline.game.mapper

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.bridge.SeatId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.TestCardRegistry
import leyline.conformance.aiPlayer
import leyline.conformance.humanPlayer
import leyline.game.snapshot.SnapshotCapture
import leyline.game.snapshotFromGame
import wotc.mtgo.gre.external.messaging.Messages.Visibility

/**
 * Verifies that [ObjectMapper.buildFromSnapshot] produces the same [GameObjectInfo] proto
 * as the legacy [ObjectMapper.buildCardObject] / [ObjectMapper.buildSharedCardObject].
 *
 * Uses [ConformanceTestBase.startWithBoard] for a synchronous board setup (~0.01s/test).
 * Each test: build legacy object → build snapshot object → compare proto equality.
 */
class ObjectMapperSnapshotTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("off-battlefield hand card: buildFromSnapshot == buildCardObject") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Hand)
            }
            val card = game.humanPlayer.getZone(ZoneType.Hand).cards.first { it.name == "Grizzly Bears" }
            val fid = ForgeCardId(card.id)
            val instanceId = b.getOrAllocInstanceId(fid).value

            b.snapshotFromGame(game)
            val snap = SnapshotCapture.run(game, b, "test")
            val cardSnap = snap.objects.getValue(fid)

            val fromSnap = ObjectMapper.buildFromSnapshot(cardSnap, instanceId, ZoneIds.P1_HAND, 1, b)
            val fromGame = ObjectMapper.buildCardObject(card, instanceId, ZoneIds.P1_HAND, 1, b)

            assertSoftly {
                fromSnap.instanceId shouldBe fromGame.instanceId
                fromSnap.grpId shouldBe fromGame.grpId
                fromSnap.zoneId shouldBe fromGame.zoneId
                fromSnap.ownerSeatId shouldBe fromGame.ownerSeatId
                fromSnap.controllerSeatId shouldBe fromGame.controllerSeatId
                fromSnap.isTapped shouldBe fromGame.isTapped
                fromSnap shouldBe fromGame
            }
        }

        test("on-battlefield creature: P/T + tapped + sickness match") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val card = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.name == "Grizzly Bears" }
            val fid = ForgeCardId(card.id)
            val instanceId = b.getOrAllocInstanceId(fid).value

            b.snapshotFromGame(game)
            val snap = SnapshotCapture.run(game, b, "test")
            val cardSnap = snap.objects.getValue(fid)

            assertSoftly {
                cardSnap.isOnBattlefield shouldBe true
                cardSnap.netPower shouldNotBe null
                cardSnap.netToughness shouldNotBe null
            }

            val fromSnap = ObjectMapper.buildFromSnapshot(
                cardSnap,
                instanceId,
                ZoneIds.BATTLEFIELD,
                1,
                b,
                Visibility.Public,
            )
            val fromGame = ObjectMapper.buildSharedCardObject(card, instanceId, ZoneIds.BATTLEFIELD, 1, 1, b, game)

            assertSoftly {
                fromSnap.power shouldBe fromGame.power
                fromSnap.toughness shouldBe fromGame.toughness
                fromSnap.isTapped shouldBe fromGame.isTapped
                fromSnap shouldBe fromGame
            }
        }

        test("graveyard card: buildFromSnapshot == buildCardObject with Public visibility") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Graveyard)
            }
            val card = game.humanPlayer.getZone(ZoneType.Graveyard).cards.first { it.name == "Grizzly Bears" }
            val fid = ForgeCardId(card.id)
            val instanceId = b.getOrAllocInstanceId(fid).value

            b.snapshotFromGame(game)
            val snap = SnapshotCapture.run(game, b, "test")
            val cardSnap = snap.objects.getValue(fid)

            cardSnap.isOnBattlefield shouldBe false

            val fromSnap = ObjectMapper.buildFromSnapshot(
                cardSnap,
                instanceId,
                ZoneIds.P1_GRAVEYARD,
                1,
                b,
                Visibility.Public,
            )
            val fromGame = ObjectMapper.buildCardObject(
                card,
                instanceId,
                ZoneIds.P1_GRAVEYARD,
                1,
                b,
                Visibility.Public,
            )

            fromSnap shouldBe fromGame
        }

        test("DFC card has othersideGrpId set in snapshot path") {
            TestCardRegistry.ensureCardRegistered("Revealing Eye")

            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Concealing Curtains", human, ZoneType.Battlefield)
            }
            val card = game.humanPlayer.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Concealing Curtains" }
            val fid = ForgeCardId(card.id)
            val instanceId = b.getOrAllocInstanceId(fid).value

            b.snapshotFromGame(game)
            val snap = SnapshotCapture.run(game, b, "test")
            val cardSnap = snap.objects.getValue(fid)

            val frontGrpId = b.cardRepository.findGrpIdByName("Concealing Curtains")!!
            val backGrpId = b.cardRepository.findGrpIdByName("Revealing Eye")!!

            val fromSnap = ObjectMapper.buildFromSnapshot(
                cardSnap,
                instanceId,
                ZoneIds.BATTLEFIELD,
                1,
                b,
                Visibility.Public,
            )
            val fromGame = ObjectMapper.buildSharedCardObject(card, instanceId, ZoneIds.BATTLEFIELD, 1, 1, b, game)

            assertSoftly {
                fromSnap.grpId shouldBe frontGrpId
                fromSnap.othersideGrpId shouldBe backGrpId
                fromSnap shouldBe fromGame
            }
        }

        test("planeswalker loyalty is captured in snapshot") {
            TestCardRegistry.ensureCardRegistered("Chandra, Torch of Defiance")

            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Chandra, Torch of Defiance", human, ZoneType.Battlefield)
            }
            val card = game.humanPlayer.getZone(ZoneType.Battlefield).cards
                .first { it.name == "Chandra, Torch of Defiance" }
            val fid = ForgeCardId(card.id)
            val instanceId = b.getOrAllocInstanceId(fid).value

            b.snapshotFromGame(game)
            val snap = SnapshotCapture.run(game, b, "test")
            val cardSnap = snap.objects.getValue(fid)

            val fromSnap = ObjectMapper.buildFromSnapshot(
                cardSnap,
                instanceId,
                ZoneIds.BATTLEFIELD,
                1,
                b,
                Visibility.Public,
            )
            val fromGame = ObjectMapper.buildSharedCardObject(card, instanceId, ZoneIds.BATTLEFIELD, 1, 1, b, game)

            fromSnap shouldBe fromGame
        }

        test("buildFromSnapshot owner/controller seats match legacy for AI cards") {
            val (b, game, _) = base.startWithBoard { _, _, ai ->
                base.addCard("Grizzly Bears", ai, ZoneType.Battlefield)
            }
            val card = game.aiPlayer.getZone(ZoneType.Battlefield).cards.first { it.name == "Grizzly Bears" }
            val fid = ForgeCardId(card.id)
            val instanceId = b.getOrAllocInstanceId(fid).value

            b.snapshotFromGame(game)
            val snap = SnapshotCapture.run(game, b, "test")
            val cardSnap = snap.objects.getValue(fid)

            assertSoftly {
                cardSnap.owner shouldBe SeatId(2)
                cardSnap.controller shouldBe SeatId(2)
            }

            val fromSnap = ObjectMapper.buildFromSnapshot(
                cardSnap,
                instanceId,
                ZoneIds.BATTLEFIELD,
                2,
                b,
                Visibility.Public,
            )
            val fromGame = ObjectMapper.buildSharedCardObject(card, instanceId, ZoneIds.BATTLEFIELD, 2, 2, b, game)

            fromSnap shouldBe fromGame
        }
    })
