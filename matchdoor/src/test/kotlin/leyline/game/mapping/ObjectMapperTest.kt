package leyline.game.mapping

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.BoardTag
import leyline.bridge.types.ForgeCardId
import leyline.conformance.BoardTestBase
import leyline.conformance.TestCardRegistry
import leyline.conformance.humanPlayer
import leyline.game.snapshot.SnapshotCapture

class ObjectMapperTest :
    FunSpec({

        tags(BoardTag)

        val base = BoardTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("DFC card has othersideGrpId set") {
            // Register back face in test card DB (startWithBoard only registers board cards)
            TestCardRegistry.ensureCardRegistered("Revealing Eye")

            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Concealing Curtains", human, ZoneType.Battlefield)
                }
            val card =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Concealing Curtains" }
            val fid = ForgeCardId(card.id)
            val instanceId = b.getOrAllocInstanceId(fid).value
            val zoneId = ZoneIds.BATTLEFIELD

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val cardSnap = snap.objects.getValue(fid)
            val obj = ObjectMapper.buildFromSnapshot(cardSnap, instanceId, zoneId, 1, b.cardProto)

            val frontGrpId = b.cardRepository.findGrpIdByName("Concealing Curtains")!!
            val backGrpId = b.cardRepository.findGrpIdByName("Revealing Eye")!!
            obj.grpId shouldBe frontGrpId
            obj.othersideGrpId shouldBe backGrpId
        }

        test("non-DFC card has othersideGrpId zero") {
            val (b, game, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val card =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Grizzly Bears" }
            val fid = ForgeCardId(card.id)
            val instanceId = b.getOrAllocInstanceId(fid).value
            val zoneId = ZoneIds.BATTLEFIELD

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val cardSnap = snap.objects.getValue(fid)
            val obj = ObjectMapper.buildFromSnapshot(cardSnap, instanceId, zoneId, 1, b.cardProto)

            obj.othersideGrpId shouldBe 0
        }

        test("buildAbilityObject sets grpId (ability) and objectSourceGrpId (host card) independently") {
            // Locks in the projection split that decouples the ability row id
            // (e.g. 86 for Cascade) from the source card's grpId. Pre-fix these
            // two fields collapsed to the same value; the fix reroutes any
            // future regression that re-collapses them straight into this test.
            val (b, _, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val abilityGrpId = 86 // Cascade
            val sourceCardGrpId = 93301 // Bloodbraid Elf (canonical Cascade host)
            val abilityIid = 9999

            val obj =
                ObjectMapper.buildAbilityObject(
                    grpId = abilityGrpId,
                    sourceCardGrpId = sourceCardGrpId,
                    instanceId = abilityIid,
                    ownerSeatId = 1,
                    cardProto = b.cardProto,
                )

            io.kotest.assertions.assertSoftly {
                obj.type shouldBe wotc.mtgo.gre.external.messaging.Messages.GameObjectType.Ability
                obj.zoneId shouldBe ZoneIds.STACK
                obj.grpId shouldBe abilityGrpId
                obj.objectSourceGrpId shouldBe sourceCardGrpId
                obj.instanceId shouldBe abilityIid
                obj.ownerSeatId shouldBe 1
                obj.controllerSeatId shouldBe 1
            }
        }
    })
