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
 * Verifies that [ObjectMapper.buildFromSnapshot] produces correct [GameObjectInfo] protos.
 *
 * Uses [ConformanceTestBase.startWithBoard] for a synchronous board setup (~0.01s/test).
 */
class ObjectMapperSnapshotTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("off-battlefield hand card: snapshot fields are correct") {
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

            assertSoftly {
                fromSnap.instanceId shouldBe instanceId
                fromSnap.zoneId shouldBe ZoneIds.P1_HAND
                fromSnap.ownerSeatId shouldBe 1
                fromSnap.controllerSeatId shouldBe 1
                fromSnap.isTapped shouldBe false
            }
        }

        test("on-battlefield creature: P/T + tapped + sickness captured") {
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

            assertSoftly {
                fromSnap.power.value shouldBe card.netPower
                fromSnap.toughness.value shouldBe card.netToughness
                fromSnap.isTapped shouldBe card.isTapped
            }
        }

        test("graveyard card: visibility and zone are correct") {
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

            assertSoftly {
                fromSnap.zoneId shouldBe ZoneIds.P1_GRAVEYARD
                fromSnap.visibility shouldBe Visibility.Public
            }
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

            assertSoftly {
                fromSnap.grpId shouldBe frontGrpId
                fromSnap.othersideGrpId shouldBe backGrpId
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

            fromSnap.loyalty.value shouldBe card.currentLoyalty
        }

        test("buildFromSnapshot owner/controller seats correct for AI cards") {
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

            assertSoftly {
                fromSnap.ownerSeatId shouldBe 2
                fromSnap.controllerSeatId shouldBe 2
            }
        }
    })
