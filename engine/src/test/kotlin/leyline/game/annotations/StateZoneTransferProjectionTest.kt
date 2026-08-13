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
import leyline.game.InMemoryCardRepository
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.GameEvent
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.StackEntry
import leyline.game.snapshot.StackSnapshot
import leyline.game.state.AnnotationProjectionState
import leyline.game.state.GameBridge
import leyline.game.state.InstanceIdRegistry
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

class StateZoneTransferProjectionTest :
    FunSpec({
        tags(UnitTag)

        test("cast mana payment uses materialized basic-land ability grpId") {
            val spellId = ForgeCardId(42)
            val forestId = ForgeCardId(7)
            val result =
                ZoneTransferDetector.detectZoneTransfers(
                    gameObjects = listOf(stateZoneGameObject(100, 12345, ZoneIds.STACK, 1)),
                    zones = listOf(stateZone(ZoneIds.STACK, ZoneType.Stack, 100), stateZone(ZoneIds.LIMBO, ZoneType.Limbo)),
                    events =
                        listOf(
                            GameEvent.SpellCast(
                                cardId = spellId,
                                seatId = SeatId(1),
                                manaPayments = listOf(GameEvent.ManaPayment(forestId, color = 5)),
                            ),
                        ),
                    context =
                        stateZoneTransferContext(
                            previousZones = mapOf(100 to ZoneIds.P1_HAND),
                            forgeIdLookup = { if (it.value == 100) spellId else null },
                            idAllocator = { InstanceIdRegistry.IdReallocation(InstanceId(100), InstanceId(200)) },
                            idLookup = { id -> if (id == forestId) InstanceId(70) else InstanceId(id.value + 1000) },
                        ).copy(manaAbilityGrpIdResolver = { id -> GrpId(if (id == forestId) 1005 else 0) }),
                )

            assertSoftly {
                result.transfers.single().category shouldBe TransferCategory.CastSpell
                result.transfers
                    .single()
                    .manaPayments
                    .single()
                    .landInstanceId shouldBe 70
                result.transfers
                    .single()
                    .manaPayments
                    .single()
                    .abilityGrpId shouldBe 1005
            }
        }

        test("hand-to-exile uses materialized Foretell state") {
            val cardId = ForgeCardId(42)
            val result =
                ZoneTransferDetector.detectZoneTransfers(
                    gameObjects = listOf(stateZoneGameObject(100, 12345, ZoneIds.EXILE, 1)),
                    zones = listOf(stateZone(ZoneIds.EXILE, ZoneType.Exile, 100), stateZone(ZoneIds.LIMBO, ZoneType.Limbo)),
                    events = emptyList(),
                    context =
                        stateZoneTransferContext(
                            previousZones = mapOf(100 to ZoneIds.P1_HAND),
                            forgeIdLookup = { if (it.value == 100) cardId else null },
                            idAllocator = { InstanceIdRegistry.IdReallocation(InstanceId(100), InstanceId(200)) },
                            idLookup = { InstanceId(100) },
                        ).copy(isForetoldLookup = { it == cardId }),
                )

            result.transfers.single().category shouldBe TransferCategory.Foretell
        }

        test("production adapter builds transfer context from snapshot facts") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val spellId = ForgeCardId(42)
            val forestId = ForgeCardId(7)
            val spellIid = bridge.getOrAllocInstanceId(spellId).value
            val forestIid = bridge.getOrAllocInstanceId(forestId).value
            bridge.recordZone(InstanceId(spellIid), ZoneIds.P1_HAND)
            val snapshot =
                GsmSnapshot.forTest(
                    objects =
                        mapOf(
                            spellId to CardSnapshot(spellId, "Spell", 4242, SeatId(1), SeatId(1)),
                            forestId to
                                CardSnapshot(
                                    forestId,
                                    "Forest",
                                    7007,
                                    SeatId(1),
                                    SeatId(1),
                                    basicLandManaAbilityGrpId = 1005,
                                ),
                        ),
                )
            val journal = AnnotationProjectionState.Planner(AnnotationProjectionState())

            val result =
                ZoneTransferAdapter.detectZoneTransfers(
                    gameObjects = listOf(stateZoneGameObject(spellIid, 4242, ZoneIds.STACK, 1)),
                    zones =
                        listOf(
                            stateZone(ZoneIds.STACK, ZoneType.Stack, spellIid),
                            stateZone(ZoneIds.LIMBO, ZoneType.Limbo),
                        ),
                    bridge = bridge,
                    snapshot = snapshot,
                    events =
                        listOf(
                            GameEvent.SpellCast(
                                cardId = spellId,
                                seatId = SeatId(1),
                                manaPayments = listOf(GameEvent.ManaPayment(forestId, color = 5)),
                            ),
                        ),
                    annotationJournal = journal,
                )

            assertSoftly {
                result.transfers.single().category shouldBe TransferCategory.CastSpell
                result.transfers.single().grpId shouldBe 4242
                result.transfers
                    .single()
                    .manaPayments
                    .single()
                    .landInstanceId shouldBe forestIid
                result.transfers
                    .single()
                    .manaPayments
                    .single()
                    .abilityGrpId shouldBe 1005
                result.idReallocations.size shouldBe 1
                journal.pendingSpellCast(spellId, 4242) shouldBe null
            }
        }

        test("production adapter resolves Paradigm source through materialized effect linkage") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val paradigmId = ForgeCardId(42)
            val effectSourceId = ForgeCardId(9)
            val abilityForgeId = 77
            val abilityId = FrameIdResolver.triggerStackAbilityForgeId(abilityForgeId)
            val abilityIid = bridge.getOrAllocInstanceId(abilityId).value
            val snapshot =
                GsmSnapshot.forTest(
                    objects = mapOf(effectSourceId to CardSnapshot(effectSourceId, "Source", 9009, SeatId(1), SeatId(1))),
                    stack =
                        StackSnapshot(
                            listOf(
                                StackEntry(
                                    forgeCardId = paradigmId,
                                    controller = SeatId(1),
                                    owner = SeatId(1),
                                    grpId = KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER,
                                    sourceCardGrpId = 4242,
                                    isSpell = false,
                                    targets = emptyList(),
                                    effectSourceForgeCardId = effectSourceId,
                                ),
                            ),
                        ),
                )
            val journal = AnnotationProjectionState.Planner(AnnotationProjectionState())
            journal.recordParadigmSourceStackIid(effectSourceId, 909)

            val result =
                ZoneTransferAdapter.detectZoneTransfers(
                    gameObjects =
                        listOf(
                            stateZoneGameObject(
                                abilityIid,
                                KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER,
                                ZoneIds.STACK,
                                1,
                                GameObjectType.Ability,
                            ),
                        ),
                    zones =
                        listOf(
                            stateZone(ZoneIds.STACK, ZoneType.Stack, abilityIid),
                            stateZone(ZoneIds.LIMBO, ZoneType.Limbo),
                        ),
                    bridge = bridge,
                    snapshot = snapshot,
                    events =
                        listOf(
                            GameEvent.SpellCast(
                                cardId = paradigmId,
                                seatId = SeatId(1),
                                isAbility = true,
                                isTrigger = true,
                                abilityForgeId = abilityForgeId,
                                abilityGrpId = KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER,
                            ),
                        ),
                    annotationJournal = journal,
                )

            result.stackAbilityAppearances.single().sourceCardInstanceId shouldBe 909
        }

        test("collapsed Paradigm lifecycle retains event-observed source when helper is absent from snapshot") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val helperId = ForgeCardId(42)
            val sourceId = ForgeCardId(9)
            val sourceStackIid = 909
            val abilityForgeId = 77
            val journal = AnnotationProjectionState.Planner(AnnotationProjectionState())
            journal.recordParadigmSourceStackIid(sourceId, sourceStackIid)
            val events =
                listOf(
                    GameEvent.SpellCast(
                        cardId = helperId,
                        seatId = SeatId(1),
                        isAbility = true,
                        isTrigger = true,
                        abilityForgeId = abilityForgeId,
                        abilityGrpId = KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER,
                        paradigmSourceCardId = sourceId,
                    ),
                    GameEvent.SpellResolved(
                        cardId = helperId,
                        hasFizzled = false,
                        isAbility = true,
                        isTrigger = true,
                        abilityForgeId = abilityForgeId,
                        abilityGrpId = KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER,
                        paradigmSourceCardId = sourceId,
                    ),
                )

            val result =
                AnnotationPipeline.computeAnnotations(
                    events = events,
                    transferResult =
                        TransferResult(
                            transfers = emptyList(),
                            patchedObjects = emptyList(),
                            patchedZones = emptyList(),
                            retiredIds = emptyList(),
                            zoneRecordings = emptyList(),
                        ),
                    actingSeat = 1,
                    bridge = bridge,
                    annotationJournal = journal,
                    snap = GsmSnapshot.forTest(),
                    frameIds = FrameIdResolver(bridge.projectionIdentityWorkspace()),
                )

            val created = result.annotations.single { AnnotationType.AbilityInstanceCreated in it.typeList }
            val deleted = result.annotations.single { AnnotationType.AbilityInstanceDeleted in it.typeList }
            val triggeringObject = result.transferPersistent.single { AnnotationType.TriggeringObject in it.typeList }
            assertSoftly {
                created.affectorId shouldBe sourceStackIid
                deleted.affectorId shouldBe sourceStackIid
                triggeringObject.affectedIdsList shouldContain sourceStackIid
            }
        }

        test("unknown stack-ability inverse is not treated as a source card") {
            val sourceId = ForgeCardId(42)
            val abilityForgeId = FrameIdResolver.stackAbilityForgeId(sourceId)
            val result =
                ZoneTransferDetector.detectZoneTransfers(
                    gameObjects = listOf(stateZoneGameObject(500, 555, ZoneIds.STACK, 1, GameObjectType.Ability)),
                    zones = listOf(stateZone(ZoneIds.STACK, ZoneType.Stack, 500), stateZone(ZoneIds.LIMBO, ZoneType.Limbo)),
                    events = emptyList(),
                    context =
                        stateZoneTransferContext(
                            previousZones = emptyMap(),
                            forgeIdLookup = { if (it.value == 500) abilityForgeId else null },
                            idAllocator = { InstanceIdRegistry.IdReallocation(InstanceId(500), InstanceId(500)) },
                            idLookup = { InstanceId(999) },
                        ).copy(forgeCardKnown = { false }),
                )

            result.stackAbilityAppearances.shouldBeEmpty()
        }
    })

private fun stateZoneGameObject(
    instanceId: Int,
    grpId: Int,
    zoneId: Int,
    ownerSeatId: Int,
    type: GameObjectType = GameObjectType.Card,
): GameObjectInfo =
    GameObjectInfo
        .newBuilder()
        .setInstanceId(instanceId)
        .setGrpId(grpId)
        .setZoneId(zoneId)
        .setOwnerSeatId(ownerSeatId)
        .setType(type)
        .build()

private fun stateZone(
    zoneId: Int,
    type: ZoneType,
    vararg objectInstanceIds: Int,
): ZoneInfo =
    ZoneInfo
        .newBuilder()
        .setZoneId(zoneId)
        .setType(type)
        .also { builder -> objectInstanceIds.forEach(builder::addObjectInstanceIds) }
        .build()

private fun stateZoneTransferContext(
    previousZones: Map<Int, Int>,
    forgeIdLookup: (InstanceId) -> ForgeCardId?,
    idAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation,
    idLookup: (ForgeCardId) -> InstanceId,
): ZoneTransferContext =
    ZoneTransferContext(
        previousZones = previousZones,
        forgeIdLookup = forgeIdLookup,
        idAllocator = idAllocator,
        idLookup = idLookup,
    )
