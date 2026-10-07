package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.InstanceId
import leyline.game.mapping.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo

class ZoneHandoffProjectionTest :
    FunSpec({
        tags(UnitTag)

        fun zone(
            zoneId: Int,
            vararg ids: Int,
        ): ZoneInfo =
            ZoneInfo
                .newBuilder()
                .setZoneId(zoneId)
                .addAllObjectInstanceIds(ids.toList())
                .build()

        fun handoff(
            oldId: Int,
            newId: Int,
            zoneId: Int,
        ): ZoneHandoff = ZoneHandoff.fromRealloc(InstanceIdRegistry.IdReallocation(InstanceId(oldId), InstanceId(newId)), zoneId)

        test("a hidden same-frame chain retires every lifetime and records only the final destination") {
            val inputZones = listOf(zone(ZoneIds.P1_LIBRARY, 100), zone(ZoneIds.LIMBO))
            val prior = ProjectionState(protoZones = mapOf(100 to ZoneIds.P1_HAND))
            val editor = prior.editor()
            val projection = ZoneHandoffProjection(emptyList(), inputZones)
            projection.applyChain(listOf(handoff(100, 200, ZoneIds.STACK), handoff(200, 201, ZoneIds.P1_LIBRARY)))
            val lifecycle = projection.lifecycle()

            editor.freeze().protoZones shouldBe prior.protoZones
            lifecycle.applyTo(editor)
            assertSoftly {
                projection.zones shouldBe listOf(zone(ZoneIds.P1_LIBRARY, 201), zone(ZoneIds.LIMBO, 100, 200))
                lifecycle.retiredIds shouldBe listOf(100, 200)
                lifecycle.zoneAssignments shouldBe listOf(201 to ZoneIds.P1_LIBRARY)
                editor.freeze().limboInstanceIds shouldBe setOf(100, 200)
                editor.freeze().protoZones shouldBe mapOf(100 to ZoneIds.P1_HAND, 201 to ZoneIds.P1_LIBRARY)
                prior.protoZones shouldBe mapOf(100 to ZoneIds.P1_HAND)
                inputZones shouldBe listOf(zone(ZoneIds.P1_LIBRARY, 100), zone(ZoneIds.LIMBO))
            }
        }

        test("same-identity resolution observes the destination without retirement or structural changes") {
            val card =
                GameObjectInfo
                    .newBuilder()
                    .setInstanceId(42)
                    .setZoneId(ZoneIds.BATTLEFIELD)
                    .build()
            val projection = ZoneHandoffProjection(listOf(card), listOf(zone(ZoneIds.BATTLEFIELD, 42), zone(ZoneIds.LIMBO)))
            projection.apply(ZoneHandoff.keepingSameInstanceId(InstanceId(42), ZoneIds.BATTLEFIELD), objectIndex = 0)
            assertSoftly {
                projection.objects shouldBe listOf(card)
                projection.lifecycle() shouldBe ZoneProjectionLifecycle(zoneAssignments = listOf(42 to ZoneIds.BATTLEFIELD))
                projection.zones.last().objectInstanceIdsList shouldBe emptyList()
            }
        }

        test("vanished ability retirement and new hidden object observations remain separate from card handoffs") {
            val projection = ZoneHandoffProjection(emptyList(), listOf(zone(ZoneIds.LIMBO)))
            projection.retire(70)
            projection.recordZone(71 to ZoneIds.P1_LIBRARY)
            val frozen = projection.lifecycle()
            projection.recordZone(72 to ZoneIds.P1_LIBRARY)
            assertSoftly {
                frozen shouldBe ZoneProjectionLifecycle(listOf(70), listOf(71 to ZoneIds.P1_LIBRARY))
                projection.zones shouldBe listOf(zone(ZoneIds.LIMBO, 70))
            }
        }

        test("staged rendering retains the private old object without duplicating an existing Limbo membership") {
            val card =
                GameObjectInfo
                    .newBuilder()
                    .setInstanceId(100)
                    .setZoneId(ZoneIds.P1_LIBRARY)
                    .build()
            val projection =
                ZoneHandoffProjection(
                    listOf(card),
                    listOf(zone(ZoneIds.P1_LIBRARY, 100), zone(ZoneIds.LIMBO, 100)),
                )
            projection.applyRetainingObject(handoff(100, 200, ZoneIds.P1_LIBRARY), 0)
            assertSoftly {
                projection.objects.map { it.instanceId to it.zoneId } shouldBe
                    listOf(100 to ZoneIds.LIMBO, 200 to ZoneIds.P1_LIBRARY)
                projection.zones shouldBe listOf(zone(ZoneIds.P1_LIBRARY, 200), zone(ZoneIds.LIMBO, 100))
                projection.lifecycle() shouldBe ZoneProjectionLifecycle(listOf(100), listOf(200 to ZoneIds.P1_LIBRARY))
                card.instanceId shouldBe 100
                card.zoneId shouldBe ZoneIds.P1_LIBRARY
            }
        }
    })
