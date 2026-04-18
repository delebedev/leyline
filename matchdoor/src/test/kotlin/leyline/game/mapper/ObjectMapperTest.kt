package leyline.game.mapper

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.TestCardRegistry
import leyline.conformance.humanPlayer
import leyline.game.snapshot.SnapshotCapture

class ObjectMapperTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("DFC card has othersideGrpId set") {
            // Register back face in test card DB (startWithBoard only registers board cards)
            TestCardRegistry.ensureCardRegistered("Revealing Eye")

            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Concealing Curtains", human, ZoneType.Battlefield)
            }
            val card = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.name == "Concealing Curtains" }
            val fid = ForgeCardId(card.id)
            val instanceId = b.getOrAllocInstanceId(fid).value
            val zoneId = ZoneIds.BATTLEFIELD

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val cardSnap = snap.objects.getValue(fid)
            val obj = ObjectMapper.buildFromSnapshot(cardSnap, instanceId, zoneId, 1, b)

            val frontGrpId = b.cardRepository.findGrpIdByName("Concealing Curtains")!!
            val backGrpId = b.cardRepository.findGrpIdByName("Revealing Eye")!!
            obj.grpId shouldBe frontGrpId
            obj.othersideGrpId shouldBe backGrpId
        }

        test("non-DFC card has othersideGrpId zero") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val card = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.name == "Grizzly Bears" }
            val fid = ForgeCardId(card.id)
            val instanceId = b.getOrAllocInstanceId(fid).value
            val zoneId = ZoneIds.BATTLEFIELD

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val cardSnap = snap.objects.getValue(fid)
            val obj = ObjectMapper.buildFromSnapshot(cardSnap, instanceId, zoneId, 1, b)

            obj.othersideGrpId shouldBe 0
        }
    })
