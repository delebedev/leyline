package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
import leyline.bridge.SeatId
import leyline.conformance.detailInt
import leyline.game.mapper.ObjectMapper
import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

/**
 * Pure unit tests for [ZoneTransferDetector.detectZoneTransfers] — the overload
 * that takes function parameters instead of [GameBridge].
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
            GameObjectInfo.newBuilder()
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
            ZoneInfo.newBuilder()
                .setZoneId(zoneId)
                .setType(type)
                .also { b -> objectInstanceIds.forEach { b.addObjectInstanceIds(it) } }
                .build()

        // -----------------------------------------------------------------------
        // Test 1: hand-to-battlefield — PlayLand
        // -----------------------------------------------------------------------

        test("detectZoneTransfers finds hand-to-battlefield transfer") {
            val obj = gameObject(instanceId = 100, grpId = 12345, zoneId = ZoneIds.BATTLEFIELD, ownerSeatId = 1)
            val zones = listOf(
                zone(ZoneIds.BATTLEFIELD, ZoneType.Battlefield, 100),
                zone(ZoneIds.LIMBO, ZoneType.Limbo),
            )
            val events = listOf(GameEvent.LandPlayed(cardId = ForgeCardId(42), seatId = SeatId(1)))
            val previousZones = mapOf(100 to ZoneIds.P1_HAND)

            val result = ZoneTransferDetector.detectZoneTransfers(
                gameObjects = listOf(obj),
                zones = zones,
                events = events,
                previousZones = previousZones,
                forgeIdLookup = { if (it.value == 100) ForgeCardId(42) else null },
                idAllocator = { _ -> InstanceIdRegistry.IdReallocation(InstanceId(100), InstanceId(200)) },
                idLookup = { fid -> InstanceId(fid.value + 1000) },
            )

            result.transfers.size shouldBe 1
            val transfer = result.transfers[0]
            transfer.category shouldBe TransferCategory.PlayLand
            transfer.origId shouldBe 100
            transfer.newId shouldBe 200
            result.retiredIds shouldBe listOf(100)
        }

        // -----------------------------------------------------------------------
        // Test 2: hand-to-stack — CastSpell
        // -----------------------------------------------------------------------

        test("detectZoneTransfers finds hand-to-stack cast") {
            val obj = gameObject(instanceId = 100, grpId = 12345, zoneId = ZoneIds.STACK, ownerSeatId = 1)
            val zones = listOf(
                zone(ZoneIds.STACK, ZoneType.Stack, 100),
                zone(ZoneIds.LIMBO, ZoneType.Limbo),
            )
            val events = listOf(GameEvent.SpellCast(cardId = ForgeCardId(42), seatId = SeatId(1)))
            val previousZones = mapOf(100 to ZoneIds.P1_HAND)

            val result = ZoneTransferDetector.detectZoneTransfers(
                gameObjects = listOf(obj),
                zones = zones,
                events = events,
                previousZones = previousZones,
                forgeIdLookup = { if (it.value == 100) ForgeCardId(42) else null },
                idAllocator = { _ -> InstanceIdRegistry.IdReallocation(InstanceId(100), InstanceId(200)) },
                idLookup = { fid -> InstanceId(fid.value + 1000) },
            )

            result.transfers.size shouldBe 1
            result.transfers[0].category shouldBe TransferCategory.CastSpell
        }

        // -----------------------------------------------------------------------
        // Test 3: stack-to-battlefield Resolve — keeps same instanceId
        // -----------------------------------------------------------------------

        test("detectZoneTransfers Resolve keeps same instanceId") {
            val obj = gameObject(instanceId = 100, grpId = 12345, zoneId = ZoneIds.BATTLEFIELD, ownerSeatId = 1)
            val zones = listOf(
                zone(ZoneIds.BATTLEFIELD, ZoneType.Battlefield, 100),
                zone(ZoneIds.LIMBO, ZoneType.Limbo),
            )
            val events = listOf(GameEvent.SpellResolved(cardId = ForgeCardId(42), hasFizzled = false))
            val previousZones = mapOf(100 to ZoneIds.STACK)

            val result = ZoneTransferDetector.detectZoneTransfers(
                gameObjects = listOf(obj),
                zones = zones,
                events = events,
                previousZones = previousZones,
                forgeIdLookup = { if (it.value == 100) ForgeCardId(42) else null },
                idAllocator = { _ -> error("should not realloc for Resolve") },
                idLookup = { fid -> InstanceId(fid.value + 1000) },
            )

            result.transfers.size shouldBe 1
            val transfer = result.transfers[0]
            transfer.category shouldBe TransferCategory.Resolve
            transfer.origId shouldBe 100
            transfer.newId shouldBe 100
            result.retiredIds.shouldBeEmpty()
        }

        // -----------------------------------------------------------------------
        // Test 4: battlefield-to-graveyard with CardDestroyed — Destroy
        // -----------------------------------------------------------------------

        test("detectZoneTransfers battlefield-to-graveyard with CardDestroyed") {
            val obj = gameObject(instanceId = 100, grpId = 12345, zoneId = ZoneIds.P1_GRAVEYARD, ownerSeatId = 1)
            val zones = listOf(
                zone(ZoneIds.P1_GRAVEYARD, ZoneType.Graveyard, 100),
                zone(ZoneIds.LIMBO, ZoneType.Limbo),
            )
            val events = listOf(GameEvent.CardDestroyed(cardId = ForgeCardId(42), seatId = SeatId(1)))
            val previousZones = mapOf(100 to ZoneIds.BATTLEFIELD)

            val result = ZoneTransferDetector.detectZoneTransfers(
                gameObjects = listOf(obj),
                zones = zones,
                events = events,
                previousZones = previousZones,
                forgeIdLookup = { if (it.value == 100) ForgeCardId(42) else null },
                idAllocator = { _ -> InstanceIdRegistry.IdReallocation(InstanceId(100), InstanceId(200)) },
                idLookup = { fid -> InstanceId(fid.value + 1000) },
            )

            result.transfers.size shouldBe 1
            result.transfers[0].category shouldBe TransferCategory.Destroy
        }

        // -----------------------------------------------------------------------
        // Test 5: no zone change — empty result
        // -----------------------------------------------------------------------

        test("detectZoneTransfers returns empty when no zone change") {
            val obj = gameObject(instanceId = 100, grpId = 12345, zoneId = ZoneIds.BATTLEFIELD, ownerSeatId = 1)
            val zones = listOf(
                zone(ZoneIds.BATTLEFIELD, ZoneType.Battlefield, 100),
                zone(ZoneIds.LIMBO, ZoneType.Limbo),
            )
            val previousZones = mapOf(100 to ZoneIds.BATTLEFIELD)

            val result = ZoneTransferDetector.detectZoneTransfers(
                gameObjects = listOf(obj),
                zones = zones,
                events = emptyList(),
                previousZones = previousZones,
                forgeIdLookup = { if (it.value == 100) ForgeCardId(42) else null },
                idAllocator = { _ -> error("should not realloc") },
                idLookup = { fid -> InstanceId(fid.value + 1000) },
            )

            result.transfers.shouldBeEmpty()
            result.retiredIds.shouldBeEmpty()
        }

        // -----------------------------------------------------------------------
        // combatAnnotations — pure overload tests
        // -----------------------------------------------------------------------

        // Test 1: no damage events → empty result
        test("combatAnnotations returns empty when no damage events") {
            val result = CombatAnnotations.combatAnnotations(
                events = emptyList(),
                idResolver = { fid -> InstanceId(fid.value + 1000) },
                previousLifeTotals = emptyMap(),
                currentLifeTotals = emptyMap(),
            )

            result.annotations.shouldBeEmpty()
            result.hasCombatDamage shouldBe false
        }

        // Test 2: creature-to-creature damage → DamageDealt
        // (PhaseOrStepModified is now emitted event-driven in Stage 2b, not by combatAnnotations)
        test("combatAnnotations produces DamageDealt for creature-to-creature") {
            val events = listOf(GameEvent.DamageDealtToCard(sourceCardId = ForgeCardId(10), targetCardId = ForgeCardId(20), amount = 3))

            val result = CombatAnnotations.combatAnnotations(
                events = events,
                idResolver = { fid -> InstanceId(fid.value + 1000) },
                previousLifeTotals = emptyMap(),
                currentLifeTotals = emptyMap(),
            )

            result.hasCombatDamage shouldBe true

            // DamageDealt is now first (PhaseOrStepModified handled elsewhere)
            val firstType = result.annotations.first().getType(0)
            firstType shouldBe AnnotationType.DamageDealt_af5a

            // DamageDealt annotation with target iid = 20 + 1000 = 1020
            val damageAnnotation = result.annotations.first { it.getType(0) == AnnotationType.DamageDealt_af5a }
            damageAnnotation.affectedIdsList.contains(1020) shouldBe true
        }

        // Test 3: creature-to-player damage + life change → ModifiedLife for seat 2
        test("combatAnnotations produces ModifiedLife when life changes") {
            val events = listOf(
                GameEvent.DamageDealtToPlayer(sourceCardId = ForgeCardId(10), targetSeatId = SeatId(2), amount = 5, combat = true),
            )

            val result = CombatAnnotations.combatAnnotations(
                events = events,
                idResolver = { fid -> InstanceId(fid.value + 1000) },
                previousLifeTotals = mapOf(1 to 20, 2 to 20),
                currentLifeTotals = mapOf(1 to 20, 2 to 15),
            )

            val lifeAnnotation = result.annotations.first { it.getType(0) == AnnotationType.ModifiedLife }
            lifeAnnotation.affectedIdsList.contains(2) shouldBe true
        }

        // Test 4: non-combat events only → empty result
        test("combatAnnotations returns empty for non-combat events only") {
            val events = listOf(GameEvent.LandPlayed(cardId = ForgeCardId(42), seatId = SeatId(1)))

            val result = CombatAnnotations.combatAnnotations(
                events = events,
                idResolver = { fid -> InstanceId(fid.value + 1000) },
                previousLifeTotals = emptyMap(),
                currentLifeTotals = emptyMap(),
            )

            result.annotations.shouldBeEmpty()
            result.hasCombatDamage shouldBe false
        }

        // -----------------------------------------------------------------------
        // Stack ability lifecycle: appearance + disappearance detection
        // -----------------------------------------------------------------------

        // Source card forgeId=42, ability forgeId=42+100_000=100_042.
        // Source card instanceId=300, ability instanceId=500.
        val sourceForgeId = ForgeCardId(42)
        val abilityForgeId = ForgeCardId(42 + ObjectMapper.STACK_ABILITY_ID_OFFSET)
        val sourceCardIid = 300
        val abilityIid = 500
        val cardGrpId = 12345

        fun abilityObject(instanceId: Int = abilityIid, grpId: Int = cardGrpId): GameObjectInfo =
            GameObjectInfo.newBuilder()
                .setInstanceId(instanceId)
                .setGrpId(grpId)
                .setZoneId(ZoneIds.STACK)
                .setOwnerSeatId(1)
                .setType(GameObjectType.Ability)
                .build()

        /** Standard lookup: maps ability instanceId → ability forgeId, source instanceId → source forgeId. */
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
            val obj = abilityObject()
            val zones = listOf(
                zone(ZoneIds.STACK, ZoneType.Stack, abilityIid),
                zone(ZoneIds.LIMBO, ZoneType.Limbo),
            )
            // Source card is on the battlefield in previous state.
            val previousZones = mapOf(sourceCardIid to ZoneIds.BATTLEFIELD)

            val result = ZoneTransferDetector.detectZoneTransfers(
                gameObjects = listOf(obj),
                zones = zones,
                events = emptyList(),
                previousZones = previousZones,
                forgeIdLookup = forgeIdLookup,
                idAllocator = noOpAllocator,
                idLookup = idLookup,
            )

            result.transfers.shouldBeEmpty()
            result.stackAbilityAppearances shouldHaveSize 1
            val a = result.stackAbilityAppearances[0]
            a.abilityInstanceId shouldBe abilityIid
            a.sourceCardInstanceId shouldBe sourceCardIid
            a.sourceZoneId shouldBe ZoneIds.BATTLEFIELD
            a.grpId shouldBe cardGrpId
        }

        test("detectZoneTransfers finds stack ability disappearance") {
            // Ability was on the stack, now gone. SpellResolved event for the source card.
            val zones = listOf(zone(ZoneIds.STACK, ZoneType.Stack), zone(ZoneIds.LIMBO, ZoneType.Limbo))
            val previousZones = mapOf(abilityIid to ZoneIds.STACK, sourceCardIid to ZoneIds.BATTLEFIELD)
            val events = listOf(GameEvent.SpellResolved(cardId = sourceForgeId, hasFizzled = false))

            val result = ZoneTransferDetector.detectZoneTransfers(
                gameObjects = emptyList(),
                zones = zones,
                events = events,
                previousZones = previousZones,
                forgeIdLookup = forgeIdLookup,
                idAllocator = noOpAllocator,
                idLookup = idLookup,
                grpIdResolver = { fid -> if (fid == sourceForgeId) cardGrpId else 0 },
            )

            result.stackAbilityDisappearances shouldHaveSize 1
            val d = result.stackAbilityDisappearances[0]
            d.abilityInstanceId shouldBe abilityIid
            d.sourceCardInstanceId shouldBe sourceCardIid
            d.grpId shouldBe cardGrpId
            d.hasFizzled shouldBe false
        }

        test("stack ability fizzle sets hasFizzled") {
            val zones = listOf(zone(ZoneIds.STACK, ZoneType.Stack), zone(ZoneIds.LIMBO, ZoneType.Limbo))
            val previousZones = mapOf(abilityIid to ZoneIds.STACK, sourceCardIid to ZoneIds.BATTLEFIELD)
            val events = listOf(GameEvent.SpellResolved(cardId = sourceForgeId, hasFizzled = true))

            val result = ZoneTransferDetector.detectZoneTransfers(
                gameObjects = emptyList(),
                zones = zones,
                events = events,
                previousZones = previousZones,
                forgeIdLookup = forgeIdLookup,
                idAllocator = noOpAllocator,
                idLookup = idLookup,
                grpIdResolver = { fid -> if (fid == sourceForgeId) cardGrpId else 0 },
            )

            result.stackAbilityDisappearances shouldHaveSize 1
            result.stackAbilityDisappearances[0].hasFizzled shouldBe true
        }

        test("annotation shape for stack ability appearance") {
            val ann = AnnotationBuilder.abilityInstanceCreated(
                abilityInstanceId = abilityIid,
                affectorId = sourceCardIid,
                sourceZoneId = ZoneIds.BATTLEFIELD,
            )

            ann.typeList shouldBe listOf(AnnotationType.AbilityInstanceCreated)
            ann.affectorId shouldBe sourceCardIid
            ann.affectedIdsList shouldBe listOf(abilityIid)
            ann.detailInt("source_zone") shouldBe ZoneIds.BATTLEFIELD
        }

        test("disappearance emits only AbilityInstanceDeleted") {
            // ResolutionStart/Complete are NOT emitted for disappeared abilities —
            // the ability's instanceId is no longer a valid game object.
            val ann = AnnotationBuilder.abilityInstanceDeleted(abilityIid, sourceCardIid)

            ann.typeList shouldBe listOf(AnnotationType.AbilityInstanceDeleted)
            ann.affectorId shouldBe sourceCardIid
            ann.affectedIdsList shouldBe listOf(abilityIid)
        }

        // -----------------------------------------------------------------------
        // Negative tests: things that should NOT produce stack ability records
        // -----------------------------------------------------------------------

        test("regular spell on stack does not produce StackAbilityAppearance") {
            // A Card (not Ability) object on the stack — e.g. a cast creature.
            val spellObj = GameObjectInfo.newBuilder()
                .setInstanceId(600)
                .setGrpId(99999)
                .setZoneId(ZoneIds.STACK)
                .setOwnerSeatId(1)
                .setType(GameObjectType.Card)
                .build()
            val zones = listOf(
                zone(ZoneIds.STACK, ZoneType.Stack, 600),
                zone(ZoneIds.LIMBO, ZoneType.Limbo),
            )

            val result = ZoneTransferDetector.detectZoneTransfers(
                gameObjects = listOf(spellObj),
                zones = zones,
                events = emptyList(),
                previousZones = emptyMap(),
                forgeIdLookup = { null },
                idAllocator = noOpAllocator,
                idLookup = { fid -> InstanceId(fid.value + 1000) },
            )

            result.stackAbilityAppearances.shouldBeEmpty()
        }

        test("ability already on stack from previous diff is not re-detected") {
            val obj = abilityObject()
            val zones = listOf(
                zone(ZoneIds.STACK, ZoneType.Stack, abilityIid),
                zone(ZoneIds.LIMBO, ZoneType.Limbo),
            )
            // Ability was already on the stack last diff.
            val previousZones = mapOf(abilityIid to ZoneIds.STACK, sourceCardIid to ZoneIds.BATTLEFIELD)

            val result = ZoneTransferDetector.detectZoneTransfers(
                gameObjects = listOf(obj),
                zones = zones,
                events = emptyList(),
                previousZones = previousZones,
                forgeIdLookup = forgeIdLookup,
                idAllocator = noOpAllocator,
                idLookup = idLookup,
            )

            result.stackAbilityAppearances.shouldBeEmpty()
            result.stackAbilityDisappearances.shouldBeEmpty()
        }
    })
