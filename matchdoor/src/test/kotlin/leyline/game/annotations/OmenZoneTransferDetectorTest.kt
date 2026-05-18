package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.bundle.InvariantChecker
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.GameEvent
import leyline.game.mapping.ZoneIds
import leyline.game.sid
import leyline.game.state.InstanceIdRegistry
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.UniqueAbilityInfo
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

class OmenZoneTransferDetectorTest :
    FunSpec({

        tags(UnitTag)

        fun zone(
            zoneId: Int,
            type: ZoneType,
            vararg objectInstanceIds: Int,
        ): ZoneInfo =
            ZoneInfo
                .newBuilder()
                .setZoneId(zoneId)
                .setType(type)
                .setVisibility(if (type == ZoneType.Library) Visibility.Hidden else Visibility.Public)
                .also { b -> objectInstanceIds.forEach { b.addObjectInstanceIds(it) } }
                .build()

        fun paradigmObject(
            instanceId: Int,
            zoneId: Int,
        ): GameObjectInfo =
            GameObjectInfo
                .newBuilder()
                .setInstanceId(instanceId)
                .setGrpId(102608)
                .setZoneId(zoneId)
                .setOwnerSeatId(1)
                .addUniqueAbilities(UniqueAbilityInfo.newBuilder().setId(1).setGrpId(KeywordAbilityIds.PARADIGM))
                .build()

        test("detectZoneTransfers expands collapsed Omen hand-to-library snapshot into cast and resolve transfers") {
            val zones =
                listOf(
                    zone(ZoneIds.P1_LIBRARY, ZoneType.Library, 100),
                    zone(ZoneIds.STACK, ZoneType.Stack),
                    zone(ZoneIds.LIMBO, ZoneType.Limbo),
                )
            val events =
                listOf(
                    GameEvent.SpellCast(cardId = ForgeCardId(42), seatId = SeatId(1), isOmen = true),
                    GameEvent.SpellResolved(cardId = ForgeCardId(42), hasFizzled = false),
                )
            val previousZones = mapOf(100 to ZoneIds.P1_HAND)
            var currentId = 100
            var nextId = 200

            val result =
                ZoneTransferDetector.detectZoneTransfers(
                    gameObjects = emptyList(),
                    zones = zones,
                    events = events,
                    previousZones = previousZones,
                    forgeIdLookup = { if (it.value == 100) ForgeCardId(42) else null },
                    idAllocator = { _ ->
                        val oldId = currentId
                        val newId = nextId++
                        currentId = newId
                        InstanceIdRegistry.IdReallocation(InstanceId(oldId), InstanceId(newId))
                    },
                    idLookup = { fid -> InstanceId(fid.value + 1000) },
                    grpIdResolver = { GrpId(95536) },
                )

            result.transfers.size shouldBe 2
            val cast = result.transfers[0]
            val resolve = result.transfers[1]
            assertSoftly {
                cast.category shouldBe TransferCategory.CastSpell
                cast.origId shouldBe 100
                cast.newId shouldBe 200
                cast.srcZoneId shouldBe ZoneIds.P1_HAND
                cast.destZoneId shouldBe ZoneIds.STACK

                resolve.category shouldBe TransferCategory.Resolve
                resolve.origId shouldBe 200
                resolve.newId shouldBe 201
                resolve.srcZoneId shouldBe ZoneIds.STACK
                resolve.destZoneId shouldBe ZoneIds.P1_LIBRARY
                resolve.grpId shouldBe 95536
                resolve.ownerSeatId shouldBe 1

                result.retiredIds shouldBe listOf(100, 200)
                result.patchedZones.first { it.zoneId == ZoneIds.P1_LIBRARY }.objectInstanceIdsList shouldBe listOf(201)
                result.patchedZones.first { it.zoneId == ZoneIds.LIMBO }.objectInstanceIdsList shouldBe listOf(100, 200)
                result.zoneRecordings shouldContain (201 to ZoneIds.P1_LIBRARY)
            }

            val annotations =
                AnnotationOrderEnforcer.enforce(
                    result.transfers.flatMap { transfer ->
                        val (transient, persistent) = TransferAnnotations.annotationsForTransfer(transfer, actingSeat = 1.sid)
                        transient + persistent
                    },
                )
            val gsm =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Full)
                    .setGameStateId(1)
                    .addAllZones(result.patchedZones)
                    .addAllAnnotations(annotations)
                    .build()
            val checker = InvariantChecker()
            checker.process(GREToClientMessage.newBuilder().setGameStateMessage(gsm).build())
            checker.violations.shouldBeEmpty()
        }

        test("detectZoneTransfers expands collapsed Paradigm hand-to-exile snapshot into cast and exile transfers") {
            val zones =
                listOf(
                    zone(ZoneIds.EXILE, ZoneType.Exile, 100),
                    zone(ZoneIds.STACK, ZoneType.Stack),
                    zone(ZoneIds.LIMBO, ZoneType.Limbo),
                )
            val events =
                listOf(
                    GameEvent.SpellCast(cardId = ForgeCardId(42), seatId = SeatId(1)),
                    GameEvent.SpellResolved(cardId = ForgeCardId(42), hasFizzled = false),
                )
            val previousZones = mapOf(100 to ZoneIds.P1_HAND)
            var currentId = 100
            var nextId = 200

            val result =
                ZoneTransferDetector.detectZoneTransfers(
                    gameObjects = listOf(paradigmObject(instanceId = 100, zoneId = ZoneIds.EXILE)),
                    zones = zones,
                    events = events,
                    previousZones = previousZones,
                    forgeIdLookup = { if (it.value == 100) ForgeCardId(42) else null },
                    idAllocator = { _ ->
                        val oldId = currentId
                        val newId = nextId++
                        currentId = newId
                        InstanceIdRegistry.IdReallocation(InstanceId(oldId), InstanceId(newId))
                    },
                    idLookup = { fid -> InstanceId(fid.value + 1000) },
                    grpIdResolver = { GrpId(102608) },
                )

            result.transfers.size shouldBe 2
            val cast = result.transfers[0]
            val exile = result.transfers[1]
            assertSoftly {
                cast.category shouldBe TransferCategory.CastSpell
                cast.origId shouldBe 100
                cast.newId shouldBe 200
                cast.srcZoneId shouldBe ZoneIds.P1_HAND
                cast.destZoneId shouldBe ZoneIds.STACK

                exile.category shouldBe TransferCategory.Exile
                exile.origId shouldBe 200
                exile.newId shouldBe 201
                exile.srcZoneId shouldBe ZoneIds.STACK
                exile.destZoneId shouldBe ZoneIds.EXILE

                result.retiredIds shouldBe listOf(100, 200)
                result.patchedZones.first { it.zoneId == ZoneIds.EXILE }.objectInstanceIdsList shouldBe listOf(201)
                result.patchedZones.first { it.zoneId == ZoneIds.LIMBO }.objectInstanceIdsList shouldBe listOf(100, 200)
                result.zoneRecordings shouldContain (201 to ZoneIds.EXILE)
            }
        }
    })
