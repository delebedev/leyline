package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.event.GameEvent
import leyline.game.iid
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ZoneIds
import leyline.game.state.InstanceIdRegistry
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

private fun stackZone(
    zoneId: Int,
    type: ZoneType,
    vararg objectInstanceIds: Int,
): ZoneInfo =
    ZoneInfo
        .newBuilder()
        .setZoneId(zoneId)
        .setType(type)
        .also { b -> objectInstanceIds.forEach { b.addObjectInstanceIds(it) } }
        .build()

private fun stackContext(
    previousZones: Map<Int, Int>,
    forgeIdLookup: (InstanceId) -> ForgeCardId?,
    idAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation,
    idLookup: (ForgeCardId) -> InstanceId,
    grpIdResolver: (ForgeCardId) -> GrpId = { GrpId(0) },
): ZoneTransferContext =
    ZoneTransferContext(
        previousZones = previousZones,
        forgeIdLookup = forgeIdLookup,
        idAllocator = idAllocator,
        idLookup = idLookup,
        grpIdResolver = grpIdResolver,
    )

class StackAbilityZoneTransferDetectorTest :
    FunSpec({
        tags(UnitTag)

        val sourceForgeId = ForgeCardId(42)
        val abilityForgeId = FrameIdResolver.stackAbilityForgeId(sourceForgeId)
        val sourceCardIid = 300
        val abilityIid = 500
        val cardGrpId = 12345

        fun abilityObject(
            instanceId: Int = abilityIid,
            grpId: Int = cardGrpId,
        ): GameObjectInfo =
            GameObjectInfo
                .newBuilder()
                .setInstanceId(instanceId)
                .setGrpId(grpId)
                .setZoneId(ZoneIds.STACK)
                .setOwnerSeatId(1)
                .setType(GameObjectType.Ability)
                .build()

        val forgeIdLookup: (InstanceId) -> ForgeCardId? = { iid ->
            when (iid.value) {
                abilityIid -> abilityForgeId
                sourceCardIid -> sourceForgeId
                else -> null
            }
        }
        val idLookup: (ForgeCardId) -> InstanceId = { fid ->
            when (fid) {
                sourceForgeId -> InstanceId(sourceCardIid)
                abilityForgeId -> InstanceId(abilityIid)
                else -> InstanceId(fid.value + 1000)
            }
        }
        val noOpAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation = { fid ->
            InstanceIdRegistry.IdReallocation(InstanceId(fid.value), InstanceId(fid.value))
        }

        test("detectZoneTransfers finds new stack ability appearance") {
            val result =
                ZoneTransferDetector.detectZoneTransfers(
                    gameObjects = listOf(abilityObject()),
                    zones = listOf(stackZone(ZoneIds.STACK, ZoneType.Stack, abilityIid), stackZone(ZoneIds.LIMBO, ZoneType.Limbo)),
                    events = emptyList(),
                    context =
                        stackContext(
                            previousZones = mapOf(sourceCardIid to ZoneIds.BATTLEFIELD),
                            forgeIdLookup = forgeIdLookup,
                            idAllocator = noOpAllocator,
                            idLookup = idLookup,
                        ),
                )

            result.transfers.shouldBeEmpty()
            result.stackAbilityAppearances shouldHaveSize 1
            val appearance = result.stackAbilityAppearances[0]
            assertSoftly {
                appearance.abilityInstanceId shouldBe abilityIid
                appearance.sourceCardInstanceId shouldBe sourceCardIid
                appearance.sourceZoneId shouldBe ZoneIds.BATTLEFIELD
                appearance.grpId shouldBe cardGrpId
            }
        }

        test("detectZoneTransfers finds stack ability disappearance") {
            val result =
                detectStackAbilityDisappearance(hasFizzled = false, forgeIdLookup, noOpAllocator, idLookup, sourceForgeId, cardGrpId)

            result.stackAbilityDisappearances shouldHaveSize 1
            val disappearance = result.stackAbilityDisappearances[0]
            assertSoftly {
                disappearance.abilityInstanceId shouldBe abilityIid
                disappearance.sourceCardInstanceId shouldBe sourceCardIid
                disappearance.grpId shouldBe cardGrpId
                disappearance.hasFizzled shouldBe false
            }
        }

        test("stack ability fizzle sets hasFizzled") {
            val result =
                detectStackAbilityDisappearance(hasFizzled = true, forgeIdLookup, noOpAllocator, idLookup, sourceForgeId, cardGrpId)

            result.stackAbilityDisappearances shouldHaveSize 1
            result.stackAbilityDisappearances[0].hasFizzled shouldBe true
        }

        test("annotation shape for stack ability appearance") {
            val ann =
                AnnotationBuilder.abilityInstanceCreated(
                    abilityInstanceId = abilityIid.iid,
                    affectorId = sourceCardIid.iid,
                    sourceZoneId = ZoneIds.BATTLEFIELD,
                )

            assertSoftly {
                ann.typeList shouldBe listOf(AnnotationType.AbilityInstanceCreated)
                ann.affectorId shouldBe sourceCardIid
                ann.affectedIdsList shouldBe listOf(abilityIid)
                ann.detailInt("source_zone") shouldBe ZoneIds.BATTLEFIELD
            }
        }

        test("disappearance emits only AbilityInstanceDeleted") {
            val ann = AnnotationBuilder.abilityInstanceDeleted(abilityIid.iid, sourceCardIid.iid)

            assertSoftly {
                ann.typeList shouldBe listOf(AnnotationType.AbilityInstanceDeleted)
                ann.affectorId shouldBe sourceCardIid
                ann.affectedIdsList shouldBe listOf(abilityIid)
            }
        }

        test("regular spell on stack does not produce StackAbilityAppearance") {
            val spellObj =
                GameObjectInfo
                    .newBuilder()
                    .setInstanceId(600)
                    .setGrpId(99999)
                    .setZoneId(ZoneIds.STACK)
                    .setOwnerSeatId(1)
                    .setType(GameObjectType.Card)
                    .build()
            val result =
                ZoneTransferDetector.detectZoneTransfers(
                    gameObjects = listOf(spellObj),
                    zones = listOf(stackZone(ZoneIds.STACK, ZoneType.Stack, 600), stackZone(ZoneIds.LIMBO, ZoneType.Limbo)),
                    events = emptyList(),
                    context =
                        stackContext(
                            previousZones = emptyMap(),
                            forgeIdLookup = { null },
                            idAllocator = noOpAllocator,
                            idLookup = { fid -> InstanceId(fid.value + 1000) },
                        ),
                )

            result.stackAbilityAppearances.shouldBeEmpty()
        }

        test("ability already on stack from previous diff is not re-detected") {
            val result =
                ZoneTransferDetector.detectZoneTransfers(
                    gameObjects = listOf(abilityObject()),
                    zones = listOf(stackZone(ZoneIds.STACK, ZoneType.Stack, abilityIid), stackZone(ZoneIds.LIMBO, ZoneType.Limbo)),
                    events = emptyList(),
                    context =
                        stackContext(
                            previousZones = mapOf(abilityIid to ZoneIds.STACK, sourceCardIid to ZoneIds.BATTLEFIELD),
                            forgeIdLookup = forgeIdLookup,
                            idAllocator = noOpAllocator,
                            idLookup = idLookup,
                        ),
                )

            result.stackAbilityAppearances.shouldBeEmpty()
            result.stackAbilityDisappearances.shouldBeEmpty()
        }

        test("same-source trigger appearances retain their exact Void classification") {
            val voidRuntimeId = 7
            val otherRuntimeId = 8
            val voidAbilityForgeId = FrameIdResolver.triggerStackAbilityForgeId(voidRuntimeId)
            val otherAbilityForgeId = FrameIdResolver.triggerStackAbilityForgeId(otherRuntimeId)
            val voidAbilityIid = 500
            val otherAbilityIid = 501
            val sourceIid = 300
            val sourceId = ForgeCardId(42)
            val objects =
                listOf(
                    abilityObject(instanceId = voidAbilityIid),
                    abilityObject(instanceId = otherAbilityIid),
                )
            val result =
                ZoneTransferDetector.detectZoneTransfers(
                    gameObjects = objects,
                    zones =
                        listOf(
                            stackZone(ZoneIds.STACK, ZoneType.Stack, voidAbilityIid, otherAbilityIid),
                            stackZone(ZoneIds.LIMBO, ZoneType.Limbo),
                        ),
                    events =
                        listOf(
                            GameEvent.SpellCast(
                                cardId = sourceId,
                                seatId = SeatId(1),
                                isAbility = true,
                                isTrigger = true,
                                abilityForgeId = otherRuntimeId,
                            ),
                            GameEvent.SpellCast(
                                cardId = sourceId,
                                seatId = SeatId(1),
                                voidTrigger = true,
                                isAbility = true,
                                isTrigger = true,
                                abilityForgeId = voidRuntimeId,
                            ),
                        ),
                    context =
                        stackContext(
                            previousZones = mapOf(sourceIid to ZoneIds.BATTLEFIELD),
                            forgeIdLookup = { iid ->
                                when (iid.value) {
                                    voidAbilityIid -> voidAbilityForgeId
                                    otherAbilityIid -> otherAbilityForgeId
                                    sourceIid -> sourceId
                                    else -> null
                                }
                            },
                            idAllocator = noOpAllocator,
                            idLookup = { fid -> if (fid == sourceId) InstanceId(sourceIid) else InstanceId(0) },
                        ),
                )

            assertSoftly {
                result.stackAbilityAppearances.map { it.abilityInstanceId } shouldBe
                    listOf(voidAbilityIid, otherAbilityIid)
                result.stackAbilityAppearances.map { it.voidTrigger } shouldBe listOf(true, false)
            }
        }
    })

private fun detectStackAbilityDisappearance(
    hasFizzled: Boolean,
    forgeIdLookup: (InstanceId) -> ForgeCardId?,
    idAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation,
    idLookup: (ForgeCardId) -> InstanceId,
    sourceForgeId: ForgeCardId,
    cardGrpId: Int,
): TransferResult =
    ZoneTransferDetector.detectZoneTransfers(
        gameObjects = emptyList(),
        zones = listOf(stackZone(ZoneIds.STACK, ZoneType.Stack), stackZone(ZoneIds.LIMBO, ZoneType.Limbo)),
        events = listOf(GameEvent.SpellResolved(cardId = sourceForgeId, hasFizzled = hasFizzled)),
        context =
            stackContext(
                previousZones = mapOf(500 to ZoneIds.STACK, 300 to ZoneIds.BATTLEFIELD),
                forgeIdLookup = forgeIdLookup,
                idAllocator = idAllocator,
                idLookup = idLookup,
                grpIdResolver = { fid -> GrpId(if (fid == sourceForgeId) cardGrpId else 0) },
            ),
    )
