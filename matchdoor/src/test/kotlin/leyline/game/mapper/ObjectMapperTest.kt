package leyline.game.mapper

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.TestCardRegistry
import leyline.conformance.humanPlayer

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
            val instanceId = b.getOrAllocInstanceId(ForgeCardId(card.id)).value
            val zoneId = ZoneIds.BATTLEFIELD

            val obj = ObjectMapper.buildSharedCardObject(card, instanceId, zoneId, 1, 1, b, game)

            val frontGrpId = b.cards.findGrpIdByName("Concealing Curtains")!!
            val backGrpId = b.cards.findGrpIdByName("Revealing Eye")!!
            obj.grpId shouldBe frontGrpId
            obj.othersideGrpId shouldBe backGrpId
        }

        test("non-DFC card has othersideGrpId zero") {
            val (b, game, _) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }
            val card = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.name == "Grizzly Bears" }
            val instanceId = b.getOrAllocInstanceId(ForgeCardId(card.id)).value
            val zoneId = ZoneIds.BATTLEFIELD

            val obj = ObjectMapper.buildSharedCardObject(card, instanceId, zoneId, 1, 1, b, game)

            obj.othersideGrpId shouldBe 0
        }
    })
