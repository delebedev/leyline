package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.annotations.AnnotationOrderEnforcer
import leyline.game.annotations.AppliedTransfer
import leyline.game.annotations.CombatAnnotations
import leyline.game.annotations.TransferCategory
import leyline.game.annotations.TransferResult
import leyline.game.annotations.ZoneTransferDetector
import leyline.game.event.DamageSourceKind
import leyline.game.event.GameEvent
import leyline.game.mapping.ZoneIds
import leyline.game.state.GameBridge
import leyline.game.state.InstanceIdRegistry
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

private fun zoneTransferContext(
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

/**
 * Pure unit tests for [leyline.game.annotations.ZoneTransferDetector.detectZoneTransfers] — the overload
 * that takes [ZoneTransferContext] instead of [leyline.game.state.GameBridge].
 *
 * No game engine, no bridge, no card DB. Each test constructs
 * [GameObjectInfo] + [ZoneInfo] data directly via proto builders.
 */
class PurePipelineTest :
    FunSpec({

        tags(UnitTag)

        fun gameObject(
            instanceId: Int,
            grpId: Int,
            zoneId: Int,
            ownerSeatId: Int,
        ): GameObjectInfo =
            GameObjectInfo
                .newBuilder()
                .setInstanceId(instanceId)
                .setGrpId(grpId)
                .setZoneId(zoneId)
                .setOwnerSeatId(ownerSeatId)
                .build()

        fun zone(
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

        test("tokenCreated affector uses resolving spell stack iid") {
            val event =
                GameEvent.TokenCreated(
                    cardId = ForgeCardId(99),
                    seatId = SeatId(1),
                    sourceCardId = ForgeCardId(42),
                )

            val affector =
                AnnotationPipeline.tokenCreatedAffectorId(
                    event,
                    resolvingStackIidsByCard = mapOf(ForgeCardId(42) to InstanceId(404)),
                    stackAbilityIid = { _, _ -> error("spell source should not use ability iid") },
                    cardIid = { InstanceId(1042) },
                )

            affector shouldBe InstanceId(404)
        }

        test("tokenCreated affector uses source ability stack iid") {
            val event =
                GameEvent.TokenCreated(
                    cardId = ForgeCardId(99),
                    seatId = SeatId(1),
                    sourceCardId = ForgeCardId(42),
                    sourceAbilityForgeId = 7,
                )

            val affector =
                AnnotationPipeline.tokenCreatedAffectorId(
                    event,
                    resolvingStackIidsByCard = mapOf(ForgeCardId(42) to InstanceId(404)),
                    stackAbilityIid = { abilityForgeId, sourceCardId ->
                        abilityForgeId shouldBe 7
                        sourceCardId shouldBe ForgeCardId(42)
                        InstanceId(421)
                    },
                    cardIid = { error("ability source should not use card iid") },
                )

            affector shouldBe InstanceId(421)
        }

        // -----------------------------------------------------------------------
        // Test 1: hand-to-battlefield — PlayLand
        // -----------------------------------------------------------------------

        test("detectZoneTransfers finds hand-to-battlefield transfer") {
            val obj = gameObject(instanceId = 100, grpId = 12345, zoneId = ZoneIds.BATTLEFIELD, ownerSeatId = 1)
            val zones =
                listOf(
                    zone(ZoneIds.BATTLEFIELD, ZoneType.Battlefield, 100),
                    zone(ZoneIds.LIMBO, ZoneType.Limbo),
                )
            val events = listOf(GameEvent.LandPlayed(cardId = ForgeCardId(42), seatId = SeatId(1)))
            val previousZones = mapOf(100 to ZoneIds.P1_HAND)

            val result =
                ZoneTransferDetector.detectZoneTransfers(
                    gameObjects = listOf(obj),
                    zones = zones,
                    events = events,
                    context =
                        zoneTransferContext(
                            previousZones = previousZones,
                            forgeIdLookup = { if (it.value == 100) ForgeCardId(42) else null },
                            idAllocator = { _ -> InstanceIdRegistry.IdReallocation(InstanceId(100), InstanceId(200)) },
                            idLookup = { fid -> InstanceId(fid.value + 1000) },
                        ),
                )

            result.transfers.size shouldBe 1
            val transfer = result.transfers[0]
            assertSoftly {
                transfer.category shouldBe TransferCategory.PlayLand
                transfer.origId shouldBe 100
                transfer.newId shouldBe 200
                result.retiredIds shouldBe listOf(100)
            }
        }

        // -----------------------------------------------------------------------
        // Test 2: hand-to-stack — CastSpell
        // -----------------------------------------------------------------------

        test("detectZoneTransfers finds hand-to-stack cast") {
            val obj = gameObject(instanceId = 100, grpId = 12345, zoneId = ZoneIds.STACK, ownerSeatId = 1)
            val zones =
                listOf(
                    zone(ZoneIds.STACK, ZoneType.Stack, 100),
                    zone(ZoneIds.LIMBO, ZoneType.Limbo),
                )
            val events = listOf(GameEvent.SpellCast(cardId = ForgeCardId(42), seatId = SeatId(1)))
            val previousZones = mapOf(100 to ZoneIds.P1_HAND)

            val result =
                ZoneTransferDetector.detectZoneTransfers(
                    gameObjects = listOf(obj),
                    zones = zones,
                    events = events,
                    context =
                        zoneTransferContext(
                            previousZones = previousZones,
                            forgeIdLookup = { if (it.value == 100) ForgeCardId(42) else null },
                            idAllocator = { _ -> InstanceIdRegistry.IdReallocation(InstanceId(100), InstanceId(200)) },
                            idLookup = { fid -> InstanceId(fid.value + 1000) },
                        ),
                )

            result.transfers.size shouldBe 1
            result.transfers[0].category shouldBe TransferCategory.CastSpell
        }

        // -----------------------------------------------------------------------
        // Test 3: stack-to-battlefield Resolve — keeps same instanceId
        // -----------------------------------------------------------------------

        test("detectZoneTransfers Resolve keeps same instanceId") {
            val obj = gameObject(instanceId = 100, grpId = 12345, zoneId = ZoneIds.BATTLEFIELD, ownerSeatId = 1)
            val zones =
                listOf(
                    zone(ZoneIds.BATTLEFIELD, ZoneType.Battlefield, 100),
                    zone(ZoneIds.LIMBO, ZoneType.Limbo),
                )
            val events = listOf(GameEvent.SpellResolved(cardId = ForgeCardId(42), hasFizzled = false))
            val previousZones = mapOf(100 to ZoneIds.STACK)

            val result =
                ZoneTransferDetector.detectZoneTransfers(
                    gameObjects = listOf(obj),
                    zones = zones,
                    events = events,
                    context =
                        zoneTransferContext(
                            previousZones = previousZones,
                            forgeIdLookup = { if (it.value == 100) ForgeCardId(42) else null },
                            idAllocator = { _ -> error("should not realloc for Resolve") },
                            idLookup = { fid -> InstanceId(fid.value + 1000) },
                        ),
                )

            result.transfers.size shouldBe 1
            val transfer = result.transfers[0]
            assertSoftly {
                transfer.category shouldBe TransferCategory.Resolve
                transfer.origId shouldBe 100
                transfer.newId shouldBe 100
                result.retiredIds.shouldBeEmpty()
            }
        }

        // -----------------------------------------------------------------------
        // Test 4: battlefield-to-graveyard with CardDestroyed — Destroy
        // -----------------------------------------------------------------------

        test("detectZoneTransfers battlefield-to-graveyard with CardDestroyed") {
            val obj = gameObject(instanceId = 100, grpId = 12345, zoneId = ZoneIds.P1_GRAVEYARD, ownerSeatId = 1)
            val zones =
                listOf(
                    zone(ZoneIds.P1_GRAVEYARD, ZoneType.Graveyard, 100),
                    zone(ZoneIds.LIMBO, ZoneType.Limbo),
                )
            val events = listOf(GameEvent.CardDestroyed(cardId = ForgeCardId(42), seatId = SeatId(1)))
            val previousZones = mapOf(100 to ZoneIds.BATTLEFIELD)

            val result =
                ZoneTransferDetector.detectZoneTransfers(
                    gameObjects = listOf(obj),
                    zones = zones,
                    events = events,
                    context =
                        zoneTransferContext(
                            previousZones = previousZones,
                            forgeIdLookup = { if (it.value == 100) ForgeCardId(42) else null },
                            idAllocator = { _ -> InstanceIdRegistry.IdReallocation(InstanceId(100), InstanceId(200)) },
                            idLookup = { fid -> InstanceId(fid.value + 1000) },
                        ),
                )

            result.transfers.size shouldBe 1
            result.transfers[0].category shouldBe TransferCategory.Destroy
        }

        // -----------------------------------------------------------------------
        // Test 5: no zone change — empty result
        // -----------------------------------------------------------------------

        test("detectZoneTransfers returns empty when no zone change") {
            val obj = gameObject(instanceId = 100, grpId = 12345, zoneId = ZoneIds.BATTLEFIELD, ownerSeatId = 1)
            val zones =
                listOf(
                    zone(ZoneIds.BATTLEFIELD, ZoneType.Battlefield, 100),
                    zone(ZoneIds.LIMBO, ZoneType.Limbo),
                )
            val previousZones = mapOf(100 to ZoneIds.BATTLEFIELD)

            val result =
                ZoneTransferDetector.detectZoneTransfers(
                    gameObjects = listOf(obj),
                    zones = zones,
                    events = emptyList(),
                    context =
                        zoneTransferContext(
                            previousZones = previousZones,
                            forgeIdLookup = { if (it.value == 100) ForgeCardId(42) else null },
                            idAllocator = { _ -> error("should not realloc") },
                            idLookup = { fid -> InstanceId(fid.value + 1000) },
                        ),
                )

            result.transfers.shouldBeEmpty()
            result.retiredIds.shouldBeEmpty()
        }

        // -----------------------------------------------------------------------
        // combatAnnotations — pure overload tests
        // -----------------------------------------------------------------------

        // Test 1: no damage events → empty result
        test("combatAnnotations returns empty when no damage events") {
            val result =
                CombatAnnotations.combatAnnotations(
                    events = emptyList(),
                    idResolver = { fid -> InstanceId(fid.value + 1000) },
                )

            result.annotations.shouldBeEmpty()
            result.hasCombatDamage shouldBe false
        }

        // Test 2: creature-to-creature damage → DamageDealt
        // (PhaseOrStepModified is now emitted event-driven in Stage 2b, not by combatAnnotations)
        test("combatAnnotations produces DamageDealt for creature-to-creature") {
            val events =
                listOf(
                    GameEvent.DamageDealtToCard(
                        sourceCardId = ForgeCardId(10),
                        targetCardId = ForgeCardId(20),
                        amount = 3,
                        sourceKind = DamageSourceKind.Combat,
                    ),
                )

            val result =
                CombatAnnotations.combatAnnotations(
                    events = events,
                    idResolver = { fid -> InstanceId(fid.value + 1000) },
                )

            result.hasCombatDamage shouldBe true
            result.resolutionOwnedAnnotations.shouldBeEmpty()

            // DamageDealt is now first (PhaseOrStepModified handled elsewhere)
            val firstType = result.annotations.first().getType(0)
            firstType shouldBe AnnotationType.DamageDealt_af5a

            // DamageDealt annotation with target iid = 20 + 1000 = 1020
            val damageAnnotation = result.annotations.first { it.getType(0) == AnnotationType.DamageDealt_af5a }
            damageAnnotation.affectedIdsList shouldContain 1020
        }

        test("combatAnnotations does not report noncombat-only damage as combat") {
            val events =
                listOf(
                    GameEvent.DamageDealtToCard(
                        sourceCardId = ForgeCardId(10),
                        targetCardId = ForgeCardId(20),
                        amount = 3,
                        sourceKind = DamageSourceKind.SpellOrAbility,
                    ),
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(30),
                        targetSeatId = SeatId(2),
                        amount = 2,
                        sourceKind = DamageSourceKind.SpellOrAbility,
                        changesLife = true,
                    ),
                )

            val result =
                CombatAnnotations.combatAnnotations(
                    events = events,
                    idResolver = { fid -> InstanceId(fid.value + 1000) },
                )

            assertSoftly {
                result.annotations.count { AnnotationType.DamageDealt_af5a in it.typeList } shouldBe 2
                result.resolutionOwnedAnnotations shouldBe result.annotations
                result.hasCombatDamage shouldBe false
            }
        }

        test("combatAnnotations does not apply combat frame shape to mixed damage") {
            val events =
                listOf(
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(10),
                        targetSeatId = SeatId(2),
                        amount = 2,
                        sourceKind = DamageSourceKind.Combat,
                        changesLife = true,
                    ),
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(30),
                        targetSeatId = SeatId(2),
                        amount = 3,
                        sourceKind = DamageSourceKind.SpellOrAbility,
                        changesLife = true,
                    ),
                )

            val result =
                CombatAnnotations.combatAnnotations(
                    events = events,
                    idResolver = { fid -> InstanceId(fid.value + 1000) },
                )

            assertSoftly {
                result.annotations.count { AnnotationType.DamageDealt_af5a in it.typeList } shouldBe 2
                result.resolutionOwnedAnnotations.shouldBeEmpty()
                result.hasCombatDamage shouldBe false
            }
        }

        test("combatAnnotations can keep pre-transfer battlefield ids for lethal combat") {
            val events =
                listOf(
                    GameEvent.DamageDealtToCard(
                        sourceCardId = ForgeCardId(10),
                        targetCardId = ForgeCardId(20),
                        amount = 3,
                        sourceKind = DamageSourceKind.Combat,
                    ),
                    GameEvent.DamageDealtToCard(
                        sourceCardId = ForgeCardId(20),
                        targetCardId = ForgeCardId(10),
                        amount = 2,
                        sourceKind = DamageSourceKind.Combat,
                    ),
                )

            val result =
                CombatAnnotations.combatAnnotations(
                    events = events,
                    idResolver = { fid ->
                        when (fid.value) {
                            10 -> InstanceId(121)
                            20 -> InstanceId(125)
                            else -> InstanceId(fid.value + 1000)
                        }
                    },
                )

            result.annotations
                .filter { it.getType(0) == AnnotationType.DamageDealt_af5a }
                .map { it.affectorId to it.affectedIdsList.single() } shouldBe
                listOf(
                    121 to 125,
                    125 to 121,
                )
        }

        // Test 3: creature-to-player damage + life change → ModifiedLife for seat 2
        test("combatAnnotations produces ModifiedLife when life changes") {
            val events =
                listOf(
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(10),
                        targetSeatId = SeatId(2),
                        amount = 5,
                        sourceKind = DamageSourceKind.Combat,
                        changesLife = true,
                    ),
                )

            val result =
                CombatAnnotations.combatAnnotations(
                    events = events,
                    idResolver = { fid -> InstanceId(fid.value + 1000) },
                )

            val lifeAnnotation = result.annotations.first { it.getType(0) == AnnotationType.ModifiedLife }
            lifeAnnotation.affectedIdsList shouldBe listOf(2)
        }

        test("assembleTransferAndCombatAnnotations defers lethal-damage destroy transfer until after DamageDealt") {
            val transferResult =
                TransferResult(
                    transfers =
                        listOf(
                            AppliedTransfer(
                                origId = 200,
                                newId = 300,
                                category = TransferCategory.Destroy,
                                srcZoneId = ZoneIds.BATTLEFIELD,
                                destZoneId = ZoneIds.P1_GRAVEYARD,
                                forgeCardId = ForgeCardId(20),
                                grpId = 12345,
                                ownerSeatId = 1,
                            ),
                        ),
                    patchedObjects = emptyList(),
                    patchedZones = emptyList(),
                    retiredIds = emptyList(),
                    zoneRecordings = emptyList(),
                )
            val combatResult =
                CombatAnnotations.combatAnnotations(
                    events =
                        listOf(
                            GameEvent.DamageDealtToCard(
                                sourceCardId = ForgeCardId(10),
                                targetCardId = ForgeCardId(20),
                                amount = 3,
                                sourceKind = DamageSourceKind.Combat,
                            ),
                        ),
                    idResolver = { fid -> InstanceId(fid.value + 1000) },
                )

            val (annotations, _) =
                AnnotationPipeline.assembleTransferAndCombatAnnotations(
                    events =
                        listOf(
                            GameEvent.DamageDealtToCard(
                                sourceCardId = ForgeCardId(10),
                                targetCardId = ForgeCardId(20),
                                amount = 3,
                                sourceKind = DamageSourceKind.Combat,
                            ),
                        ),
                    transferResult = transferResult,
                    actingSeat = 1,
                    combatResult = combatResult,
                )

            val types = annotations.map { it.getType(0) }
            val damageIdx = types.indexOf(AnnotationType.DamageDealt_af5a)
            val oicIdx = types.indexOf(AnnotationType.ObjectIdChanged)
            val ztIdx = types.indexOf(AnnotationType.ZoneTransfer_af5a)
            assertSoftly {
                damageIdx shouldBe 0
                oicIdx shouldBe 1
                ztIdx shouldBe 2
            }
            types.filter { it == AnnotationType.DamagedThisTurn } shouldBe emptyList()
        }

        test("assembleTransferAndCombatAnnotations keeps non-damage destroy transfer before combat block") {
            val transferResult =
                TransferResult(
                    transfers =
                        listOf(
                            AppliedTransfer(
                                origId = 200,
                                newId = 300,
                                category = TransferCategory.Destroy,
                                srcZoneId = ZoneIds.BATTLEFIELD,
                                destZoneId = ZoneIds.P1_GRAVEYARD,
                                forgeCardId = ForgeCardId(20),
                                grpId = 12345,
                                ownerSeatId = 1,
                            ),
                        ),
                    patchedObjects = emptyList(),
                    patchedZones = emptyList(),
                    retiredIds = emptyList(),
                    zoneRecordings = emptyList(),
                )
            val combatResult =
                CombatAnnotations.combatAnnotations(
                    events =
                        listOf(
                            GameEvent.DamageDealtToCard(
                                sourceCardId = ForgeCardId(10),
                                targetCardId = ForgeCardId(99),
                                amount = 3,
                                sourceKind = DamageSourceKind.Combat,
                            ),
                        ),
                    idResolver = { fid -> InstanceId(fid.value + 1000) },
                )

            val (annotations, _) =
                AnnotationPipeline.assembleTransferAndCombatAnnotations(
                    events =
                        listOf(
                            GameEvent.DamageDealtToCard(
                                sourceCardId = ForgeCardId(10),
                                targetCardId = ForgeCardId(99),
                                amount = 3,
                                sourceKind = DamageSourceKind.Combat,
                            ),
                        ),
                    transferResult = transferResult,
                    actingSeat = 1,
                    combatResult = combatResult,
                )

            val types = annotations.map { it.getType(0) }
            assertSoftly {
                types.indexOf(AnnotationType.ObjectIdChanged) shouldBe 0
                types.indexOf(AnnotationType.ZoneTransfer_af5a) shouldBe 1
                types.indexOf(AnnotationType.DamageDealt_af5a) shouldBe 2
            }
        }

        test("computeAnnotations keeps damage on pre-transfer ids for lethal combat") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val attackerFid = ForgeCardId(10)
            val blockerFid = ForgeCardId(20)
            val oldAttacker = bridge.getOrAllocInstanceId(attackerFid).value
            val oldBlocker = bridge.getOrAllocInstanceId(blockerFid).value
            val newAttacker = bridge.reallocInstanceId(attackerFid).new.value
            val newBlocker = bridge.reallocInstanceId(blockerFid).new.value

            val transferResult =
                TransferResult(
                    transfers =
                        listOf(
                            AppliedTransfer(
                                origId = oldAttacker,
                                newId = newAttacker,
                                category = TransferCategory.Destroy,
                                srcZoneId = ZoneIds.BATTLEFIELD,
                                destZoneId = ZoneIds.P1_GRAVEYARD,
                                forgeCardId = attackerFid,
                                grpId = 111,
                                ownerSeatId = 1,
                            ),
                            AppliedTransfer(
                                origId = oldBlocker,
                                newId = newBlocker,
                                category = TransferCategory.Destroy,
                                srcZoneId = ZoneIds.BATTLEFIELD,
                                destZoneId = ZoneIds.P2_GRAVEYARD,
                                forgeCardId = blockerFid,
                                grpId = 222,
                                ownerSeatId = 2,
                            ),
                        ),
                    patchedObjects = emptyList(),
                    patchedZones = emptyList(),
                    retiredIds = emptyList(),
                    zoneRecordings = emptyList(),
                )
            val events =
                listOf(
                    GameEvent.DamageDealtToCard(
                        sourceCardId = attackerFid,
                        targetCardId = blockerFid,
                        amount = 3,
                        sourceKind = DamageSourceKind.Combat,
                    ),
                    GameEvent.DamageDealtToCard(
                        sourceCardId = blockerFid,
                        targetCardId = attackerFid,
                        amount = 5,
                        sourceKind = DamageSourceKind.Combat,
                    ),
                )

            val pipeline = AnnotationPipeline.computeAnnotations(events, transferResult, actingSeat = 1, bridge = bridge)
            val ordered = AnnotationOrderEnforcer.enforce(pipeline.annotations)

            ordered
                .filter { it.getType(0) == AnnotationType.DamageDealt_af5a }
                .map { it.affectorId to it.affectedIdsList.single() } shouldBe
                listOf(
                    oldAttacker to oldBlocker,
                    oldBlocker to oldAttacker,
                )

            val damageIndices =
                ordered.mapIndexedNotNull { index, ann ->
                    if (ann.getType(0) == AnnotationType.DamageDealt_af5a) index else null
                }
            val firstOicIdx = ordered.indexOfFirst { it.getType(0) == AnnotationType.ObjectIdChanged }
            assertSoftly {
                damageIndices.first() shouldBe 0
                damageIndices.last() shouldBe 1
                firstOicIdx shouldBeGreaterThan damageIndices.last()
            }

            ordered
                .filter { it.getType(0) == AnnotationType.ObjectIdChanged }
                .map { it.detailInt("orig_id") to it.detailInt("new_id") } shouldBe
                listOf(
                    oldAttacker to newAttacker,
                    oldBlocker to newBlocker,
                )
        }

        // Test 4: non-combat events only → empty result
        test("combatAnnotations returns empty for non-combat events only") {
            val events = listOf(GameEvent.LandPlayed(cardId = ForgeCardId(42), seatId = SeatId(1)))

            val result =
                CombatAnnotations.combatAnnotations(
                    events = events,
                    idResolver = { fid -> InstanceId(fid.value + 1000) },
                )

            result.annotations.shouldBeEmpty()
            result.hasCombatDamage shouldBe false
        }
    })
