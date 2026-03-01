package forge.nexus.game

import forge.nexus.game.mapper.ZoneIds
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.Assert.fail
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType

@Test(groups = ["unit"])
class AnnotationBuilderTest {

    @Test
    fun zoneTransferAnnotation() {
        val ann = AnnotationBuilder.zoneTransfer(
            instanceId = 100,
            srcZoneId = 31, // Hand
            destZoneId = 28, // Battlefield
            category = "PlayLand",
        )
        assertTrue(ann.typeList.contains(AnnotationType.ZoneTransfer_af5a))
        // zone_src/zone_dest use Int32 type (matches real recordings)
        val zoneSrc = ann.detailsList.first { it.key == "zone_src" }
        assertEquals(zoneSrc.getValueInt32(0), 31)
        val zoneDest = ann.detailsList.first { it.key == "zone_dest" }
        assertEquals(zoneDest.getValueInt32(0), 28)
        val category = ann.detailsList.first { it.key == "category" }
        assertEquals(category.getValueString(0), "PlayLand")
        assertTrue(ann.affectedIdsList.contains(100))
    }

    @Test
    fun castSpellAnnotation() {
        val ann = AnnotationBuilder.zoneTransfer(
            instanceId = 105,
            srcZoneId = 31, // Hand
            destZoneId = 27, // Stack
            category = "CastSpell",
        )
        val zoneSrc = ann.detailsList.first { it.key == "zone_src" }
        assertEquals(zoneSrc.getValueInt32(0), 31)
        val zoneDest = ann.detailsList.first { it.key == "zone_dest" }
        assertEquals(zoneDest.getValueInt32(0), 27)
        val category = ann.detailsList.first { it.key == "category" }
        assertEquals(category.getValueString(0), "CastSpell")
    }

    @Test
    fun zoneTransferWithActingSeat() {
        val ann = AnnotationBuilder.zoneTransfer(
            instanceId = 200,
            srcZoneId = 27,
            destZoneId = 28,
            category = "Resolve",
            actingSeatId = 1,
        )
        assertEquals(ann.affectorId, 1, "actingSeatId should be set as affectorId")
        assertTrue(ann.affectedIdsList.contains(200))
    }

    @Test
    fun zoneTransferWithoutActingSeatHasZeroAffector() {
        val ann = AnnotationBuilder.zoneTransfer(
            instanceId = 200,
            srcZoneId = 31,
            destZoneId = 28,
            category = "PlayLand",
        )
        assertEquals(ann.affectorId, 0, "Default affectorId should be 0 when actingSeatId not set")
    }

    // --- ObjectIdChanged ---

    @Test
    fun objectIdChangedHasOrigAndNewId() {
        val ann = AnnotationBuilder.objectIdChanged(origId = 100, newId = 150)
        assertTrue(ann.typeList.contains(AnnotationType.ObjectIdChanged))
        assertTrue(ann.affectedIdsList.contains(100), "affectedIds should contain orig_id")

        val origDetail = ann.detailsList.first { it.key == "orig_id" }
        assertEquals(origDetail.type, KeyValuePairValueType.Int32)
        assertEquals(origDetail.getValueInt32(0), 100)

        val newDetail = ann.detailsList.first { it.key == "new_id" }
        assertEquals(newDetail.type, KeyValuePairValueType.Int32)
        assertEquals(newDetail.getValueInt32(0), 150)
    }

    @Test
    fun objectIdChangedNoAffectorId() {
        val ann = AnnotationBuilder.objectIdChanged(origId = 100, newId = 200)
        assertEquals(ann.affectorId, 0, "ObjectIdChanged should have no affectorId (default 0)")
    }

    // --- UserActionTaken ---

    @Test
    fun userActionTakenFields() {
        val ann = AnnotationBuilder.userActionTaken(
            instanceId = 300,
            seatId = 1,
            actionType = 3,
            abilityGrpId = 0,
        )
        assertTrue(ann.typeList.contains(AnnotationType.UserActionTaken))
        assertEquals(ann.affectorId, 1, "affectorId should be the acting seat")
        assertTrue(ann.affectedIdsList.contains(300))

        val actionType = ann.detailsList.first { it.key == "actionType" }
        assertEquals(actionType.type, KeyValuePairValueType.Int32)
        assertEquals(actionType.getValueInt32(0), 3)

        val abilityGrpId = ann.detailsList.first { it.key == "abilityGrpId" }
        assertEquals(abilityGrpId.type, KeyValuePairValueType.Int32)
        assertEquals(abilityGrpId.getValueInt32(0), 0)
    }

    @Test
    fun userActionTakenCastType() {
        val ann = AnnotationBuilder.userActionTaken(instanceId = 400, seatId = 2, actionType = 1)
        val actionType = ann.detailsList.first { it.key == "actionType" }
        assertEquals(actionType.getValueInt32(0), 1, "actionType=1 for Cast")
        assertEquals(ann.affectorId, 2, "affectorId should be seat 2")
    }

    // --- ResolutionStart ---

    @Test
    fun resolutionStartFields() {
        val ann = AnnotationBuilder.resolutionStart(instanceId = 500, grpId = 12345)
        assertTrue(ann.typeList.contains(AnnotationType.ResolutionStart))
        assertEquals(ann.affectorId, 500, "affectorId should be instanceId")
        assertTrue(ann.affectedIdsList.contains(500), "affectedIds should contain instanceId")

        val grpid = ann.detailsList.first { it.key == "grpid" }
        assertEquals(grpid.type, KeyValuePairValueType.Uint32)
        assertEquals(grpid.getValueUint32(0), 12345)
    }

    // --- ResolutionComplete ---

    @Test
    fun resolutionCompleteFields() {
        val ann = AnnotationBuilder.resolutionComplete(instanceId = 500, grpId = 12345)
        assertTrue(ann.typeList.contains(AnnotationType.ResolutionComplete))
        assertEquals(ann.affectorId, 500, "affectorId should be instanceId")
        assertTrue(ann.affectedIdsList.contains(500), "affectedIds should contain instanceId")

        val grpid = ann.detailsList.first { it.key == "grpid" }
        assertEquals(grpid.type, KeyValuePairValueType.Uint32)
        assertEquals(grpid.getValueUint32(0), 12345)
    }

    // --- PhaseOrStepModified ---

    @Test
    fun phaseOrStepModifiedHasContent() {
        val ann = AnnotationBuilder.phaseOrStepModified(activeSeat = 2, phase = 1, step = 2)
        assertTrue(ann.typeList.contains(AnnotationType.PhaseOrStepModified))
        assertTrue(ann.affectedIdsList.contains(2), "Should have activeSeat in affectedIds")
        val detailKeys = ann.detailsList.map { it.key }.toSet()
        assertTrue("phase" in detailKeys, "Should have phase detail")
        assertTrue("step" in detailKeys, "Should have step detail")
    }

    // --- ManaPaid ---

    @Test
    fun manaPaidFields() {
        val ann = AnnotationBuilder.manaPaid(instanceId = 600, manaId = 5, color = "Green")
        assertTrue(ann.typeList.contains(AnnotationType.ManaPaid))
        assertTrue(ann.affectedIdsList.contains(600))
        assertEquals(ann.affectorId, 0, "ManaPaid has no affectorId")

        val id = ann.detailsList.first { it.key == "id" }
        assertEquals(id.type, KeyValuePairValueType.Int32)
        assertEquals(id.getValueInt32(0), 5)

        val color = ann.detailsList.first { it.key == "color" }
        assertEquals(color.type, KeyValuePairValueType.String)
        assertEquals(color.getValueString(0), "Green")
    }

    @Test
    fun manaPaidDefaults() {
        val ann = AnnotationBuilder.manaPaid(instanceId = 600)
        // Defaults: manaId=0, color=""
        val id = ann.detailsList.first { it.key == "id" }
        assertEquals(id.getValueInt32(0), 0, "default manaId should be 0")
        val color = ann.detailsList.first { it.key == "color" }
        assertEquals(color.getValueString(0), "", "default color should be empty")
    }

    // --- TappedUntappedPermanent ---

    @Test
    fun tappedUntappedPermanentFields() {
        val ann = AnnotationBuilder.tappedUntappedPermanent(permanentId = 700, abilityId = 800)
        assertTrue(ann.typeList.contains(AnnotationType.TappedUntappedPermanent))
        assertEquals(ann.affectorId, 800, "affectorId should be the ability instanceId")
        assertTrue(ann.affectedIdsList.contains(700), "affectedIds should contain the permanent")

        val tapped = ann.detailsList.first { it.key == "tapped" }
        assertEquals(tapped.type, KeyValuePairValueType.Uint32)
        assertEquals(tapped.getValueUint32(0), 1, "tapped should be 1 (true)")
    }

    @Test
    fun tappedUntappedPermanentUntapVariant() {
        val ann = AnnotationBuilder.tappedUntappedPermanent(permanentId = 700, abilityId = 800, tapped = false)
        assertTrue(ann.typeList.contains(AnnotationType.TappedUntappedPermanent))
        assertEquals(ann.affectorId, 800)
        assertTrue(ann.affectedIdsList.contains(700))

        val tapped = ann.detailsList.first { it.key == "tapped" }
        assertEquals(tapped.getValueUint32(0), 0, "tapped should be 0 (false/untap)")
    }

    // --- AbilityInstanceCreated ---

    @Test
    fun abilityInstanceCreatedFields() {
        val ann = AnnotationBuilder.abilityInstanceCreated(instanceId = 900, sourceZoneId = 31)
        assertTrue(ann.typeList.contains(AnnotationType.AbilityInstanceCreated))
        assertTrue(ann.affectedIdsList.contains(900))
        assertEquals(ann.affectorId, 0, "No affectorId")

        val srcZone = ann.detailsList.first { it.key == "source_zone" }
        assertEquals(srcZone.type, KeyValuePairValueType.Int32)
        assertEquals(srcZone.getValueInt32(0), 31, "source_zone should be Hand (31)")
    }

    @Test
    fun abilityInstanceCreatedDefaultZone() {
        val ann = AnnotationBuilder.abilityInstanceCreated(instanceId = 900)
        val srcZone = ann.detailsList.first { it.key == "source_zone" }
        assertEquals(srcZone.getValueInt32(0), 0, "default source_zone should be 0")
    }

    // --- AbilityInstanceDeleted ---

    @Test
    fun abilityInstanceDeletedFields() {
        val ann = AnnotationBuilder.abilityInstanceDeleted(instanceId = 900)
        assertTrue(ann.typeList.contains(AnnotationType.AbilityInstanceDeleted))
        assertTrue(ann.affectedIdsList.contains(900))
        assertEquals(ann.affectorId, 0, "No affectorId")
    }

    // --- EnteredZoneThisTurn ---

    @Test
    fun enteredZoneThisTurnFields() {
        val ann = AnnotationBuilder.enteredZoneThisTurn(zoneId = ZoneIds.BATTLEFIELD, 100, 200)
        assertTrue(ann.typeList.contains(AnnotationType.EnteredZoneThisTurn))
        assertEquals(ann.affectorId, 28, "affectorId should be the zone ID")
        assertTrue(ann.affectedIdsList.contains(100))
        assertTrue(ann.affectedIdsList.contains(200))
        assertEquals(ann.affectedIdsCount, 2, "Should have 2 affected IDs")
    }

    @Test
    fun enteredZoneThisTurnSingleId() {
        val ann = AnnotationBuilder.enteredZoneThisTurn(zoneId = ZoneIds.BATTLEFIELD, 100)
        assertEquals(ann.affectedIdsCount, 1)
        assertTrue(ann.affectedIdsList.contains(100))
    }

    // --- DamageDealt ---

    @Test
    fun damageDealtFields() {
        val ann = AnnotationBuilder.damageDealt(sourceInstanceId = 1000, amount = 3)
        assertTrue(ann.typeList.contains(AnnotationType.DamageDealt_af5a))
        assertTrue(ann.affectedIdsList.contains(1000))

        val damage = ann.detailsList.first { it.key == "damage" }
        assertEquals(damage.type, KeyValuePairValueType.Uint32)
        assertEquals(damage.getValueUint32(0), 3)

        // type defaults to 1 (combat)
        val type = ann.detailsList.first { it.key == "type" }
        assertEquals(type.type, KeyValuePairValueType.Uint32)
        assertEquals(type.getValueUint32(0), 1, "default type should be 1 (combat)")

        // markDamage defaults to amount
        val markDamage = ann.detailsList.first { it.key == "markDamage" }
        assertEquals(markDamage.type, KeyValuePairValueType.Uint32)
        assertEquals(markDamage.getValueUint32(0), 3, "markDamage should default to damage amount")
    }

    @Test
    fun damageDealtNonCombat() {
        val ann = AnnotationBuilder.damageDealt(sourceInstanceId = 1000, amount = 2, type = 0, markDamage = 2)
        val type = ann.detailsList.first { it.key == "type" }
        assertEquals(type.getValueUint32(0), 0, "type=0 for non-combat damage")
    }

    // --- ModifiedLife ---

    @Test
    fun modifiedLifePositiveDelta() {
        val ann = AnnotationBuilder.modifiedLife(playerSeatId = 1, lifeDelta = 3)
        assertTrue(ann.typeList.contains(AnnotationType.ModifiedLife))
        assertTrue(ann.affectedIdsList.contains(1))

        val delta = ann.detailsList.first { it.key == "delta" }
        assertEquals(delta.type, KeyValuePairValueType.Int32, "delta uses Int32 (signed)")
        assertEquals(delta.getValueInt32(0), 3)
    }

    @Test
    fun modifiedLifeNegativeDelta() {
        val ann = AnnotationBuilder.modifiedLife(playerSeatId = 2, lifeDelta = -5)
        val delta = ann.detailsList.first { it.key == "delta" }
        assertEquals(delta.getValueInt32(0), -5, "Negative life delta should be preserved")
    }

    // --- SyntheticEvent ---

    @Test
    fun syntheticEventMinimal() {
        val ann = AnnotationBuilder.syntheticEvent()
        assertTrue(ann.typeList.contains(AnnotationType.SyntheticEvent))
        assertEquals(ann.affectorId, 0)
        assertEquals(ann.affectedIdsCount, 0)
    }

    // --- TokenCreated (Group B) ---

    @Test
    fun tokenCreatedFields() {
        val ann = AnnotationBuilder.tokenCreated(instanceId = 1100)
        assertTrue(ann.typeList.contains(AnnotationType.TokenCreated))
        assertTrue(ann.affectedIdsList.contains(1100))
        assertEquals(ann.affectorId, 0, "TokenCreated has no affectorId")
        assertEquals(ann.detailsCount, 0, "TokenCreated has no detail keys")
    }

    // --- TokenDeleted (Group B) ---

    @Test
    fun tokenDeletedFields() {
        val ann = AnnotationBuilder.tokenDeleted(instanceId = 1150)
        assertTrue(ann.typeList.contains(AnnotationType.TokenDeleted))
        assertEquals(ann.affectorId, 1150, "affectorId should be the token instanceId")
        assertTrue(ann.affectedIdsList.contains(1150))
        assertEquals(ann.affectedIdsCount, 1, "TokenDeleted has one affected ID")
        assertEquals(ann.detailsCount, 0, "TokenDeleted has no detail keys")
    }

    // --- CounterAdded (Group B) ---

    @Test
    fun counterAddedFields() {
        val ann = AnnotationBuilder.counterAdded(instanceId = 100, counterType = "P1P1", amount = 2)
        assertTrue(ann.typeList.contains(AnnotationType.CounterAdded))
        assertTrue(ann.affectedIdsList.contains(100))

        val type = ann.detailsList.first { it.key == "counter_type" }
        assertEquals(type.type, KeyValuePairValueType.String)
        assertEquals(type.getValueString(0), "P1P1")

        val txn = ann.detailsList.first { it.key == "transaction_amount" }
        assertEquals(txn.type, KeyValuePairValueType.Int32)
        assertEquals(txn.getValueInt32(0), 2)
    }

    // --- CounterRemoved (Group B) ---

    @Test
    fun counterRemovedFields() {
        val ann = AnnotationBuilder.counterRemoved(instanceId = 200, counterType = "LOYALTY", amount = 3)
        assertTrue(ann.typeList.contains(AnnotationType.CounterRemoved))
        assertTrue(ann.affectedIdsList.contains(200))

        val type = ann.detailsList.first { it.key == "counter_type" }
        assertEquals(type.getValueString(0), "LOYALTY")

        val txn = ann.detailsList.first { it.key == "transaction_amount" }
        assertEquals(txn.getValueInt32(0), 3)
    }

    // --- Shuffle (Group B) ---

    @Test
    fun shuffleFields() {
        val ann = AnnotationBuilder.shuffle(seatId = 1)
        assertTrue(ann.typeList.contains(AnnotationType.Shuffle))
        assertTrue(ann.affectedIdsList.contains(1))
    }

    // --- ModifiedPower (Group B) ---

    @Test
    fun modifiedPowerFields() {
        val ann = AnnotationBuilder.modifiedPower(instanceId = 1200, value = 5)
        assertTrue(ann.typeList.contains(AnnotationType.ModifiedPower))
        assertTrue(ann.affectedIdsList.contains(1200))
        assertEquals(ann.affectorId, 0, "ModifiedPower has no affectorId")

        val value = ann.detailsList.first { it.key == "value" }
        assertEquals(value.type, KeyValuePairValueType.Int32)
        assertEquals(value.getValueInt32(0), 5)
    }

    // --- ModifiedToughness (Group B) ---

    @Test
    fun modifiedToughnessFields() {
        val ann = AnnotationBuilder.modifiedToughness(instanceId = 1300, value = 3)
        assertTrue(ann.typeList.contains(AnnotationType.ModifiedToughness))
        assertTrue(ann.affectedIdsList.contains(1300))

        val value = ann.detailsList.first { it.key == "value" }
        assertEquals(value.type, KeyValuePairValueType.Int32)
        assertEquals(value.getValueInt32(0), 3)
    }

    // --- RemoveAttachment (Group A+) ---

    @Test
    fun removeAttachmentFields() {
        val ann = AnnotationBuilder.removeAttachment(auraIid = 1400)
        assertTrue(ann.typeList.contains(AnnotationType.RemoveAttachment))
        assertTrue(ann.affectedIdsList.contains(1400))
        assertEquals(ann.affectedIdsCount, 1, "RemoveAttachment has only the aura id")
    }

    // --- AttachmentCreated (Group A+) ---

    @Test
    fun attachmentCreatedFields() {
        val ann = AnnotationBuilder.attachmentCreated(auraIid = 1500, targetIid = 1600)
        assertTrue(ann.typeList.contains(AnnotationType.AttachmentCreated))
        assertEquals(ann.affectorId, 0, "no affectorId")
        assertEquals(ann.affectedIdsList, listOf(1500, 1600), "affectedIds=[aura, target]")
    }

    // --- Attachment (Group A+ persistent) ---

    @Test
    fun attachmentFields() {
        val ann = AnnotationBuilder.attachment(auraIid = 1500, targetIid = 1600)
        assertTrue(ann.typeList.contains(AnnotationType.Attachment))
        assertEquals(ann.affectorId, 0, "no affectorId")
        assertEquals(ann.affectedIdsList, listOf(1500, 1600), "affectedIds=[aura, target]")
    }

    // --- Scry (Group B) ---

    @Test
    fun scryFields() {
        val ann = AnnotationBuilder.scry(seatId = 1, topCount = 2, bottomCount = 1)
        assertTrue(ann.typeList.contains(AnnotationType.Scry_af5a))
        assertTrue(ann.affectedIdsList.contains(1))

        val top = ann.detailsList.first { it.key == "topCount" }
        assertEquals(top.type, KeyValuePairValueType.Int32)
        assertEquals(top.getValueInt32(0), 2)

        val bottom = ann.detailsList.first { it.key == "bottomCount" }
        assertEquals(bottom.getValueInt32(0), 1)
    }

    // =======================================================================
    // Stateless detail-key shape tests
    //
    // Verify each builder method produces the exact set of detail keys
    // the real Arena server sends (from golden recording reference).
    // Catches field additions/removals at the builder level without
    // needing game state or golden files.
    // =======================================================================

    private fun detailKeys(ann: AnnotationInfo): Set<String> =
        ann.detailsList.map { it.key }.toSet()

    @Test(description = "DamageDealt shape: {damage, type, markDamage} — matches golden combat-damage.bin gsId=126")
    fun damageDealtDetailKeyShape() {
        val ann = AnnotationBuilder.damageDealt(sourceInstanceId = 1, amount = 3)
        assertEquals(
            detailKeys(ann),
            setOf("damage", "type", "markDamage"),
            "DamageDealt must have all three keys for combat damage animation",
        )
    }

    @Test(description = "ManaPaid shape: {id, color} — matches golden stack-resolve.bin gsId=66")
    fun manaPaidDetailKeyShape() {
        val ann = AnnotationBuilder.manaPaid(instanceId = 1, manaId = 1, color = "Green")
        assertEquals(
            detailKeys(ann),
            setOf("id", "color"),
            "ManaPaid must have id and color for mana payment tracking",
        )
    }

    @Test(description = "AbilityInstanceCreated shape: {source_zone} — matches golden stack-resolve.bin gsId=66")
    fun abilityInstanceCreatedDetailKeyShape() {
        val ann = AnnotationBuilder.abilityInstanceCreated(instanceId = 1, sourceZoneId = 31)
        assertEquals(
            detailKeys(ann),
            setOf("source_zone"),
            "AbilityInstanceCreated must have source_zone for animation origin",
        )
    }

    @Test(description = "ZoneTransfer shape: {zone_src, zone_dest, category}")
    fun zoneTransferDetailKeyShape() {
        val ann = AnnotationBuilder.zoneTransfer(1, 31, 28, "PlayLand")
        assertEquals(detailKeys(ann), setOf("zone_src", "zone_dest", "category"))
    }

    @Test(description = "ResolutionStart shape: {grpid}")
    fun resolutionStartDetailKeyShape() {
        val ann = AnnotationBuilder.resolutionStart(1, 12345)
        assertEquals(detailKeys(ann), setOf("grpid"))
    }

    @Test(description = "ResolutionComplete shape: {grpid}")
    fun resolutionCompleteDetailKeyShape() {
        val ann = AnnotationBuilder.resolutionComplete(1, 12345)
        assertEquals(detailKeys(ann), setOf("grpid"))
    }

    @Test(description = "UserActionTaken shape: {actionType, abilityGrpId}")
    fun userActionTakenDetailKeyShape() {
        val ann = AnnotationBuilder.userActionTaken(1, 1, 1, 0)
        assertEquals(detailKeys(ann), setOf("actionType", "abilityGrpId"))
    }

    @Test(description = "TappedUntappedPermanent shape: {tapped}")
    fun tappedUntappedDetailKeyShape() {
        val ann = AnnotationBuilder.tappedUntappedPermanent(1, 2)
        assertEquals(detailKeys(ann), setOf("tapped"))
    }

    @Test(description = "ObjectIdChanged shape: {orig_id, new_id}")
    fun objectIdChangedDetailKeyShape() {
        val ann = AnnotationBuilder.objectIdChanged(1, 2)
        assertEquals(detailKeys(ann), setOf("orig_id", "new_id"))
    }

    @Test(description = "PhaseOrStepModified shape: {phase, step}")
    fun phaseOrStepModifiedDetailKeyShape() {
        val ann = AnnotationBuilder.phaseOrStepModified(1, 1, 2)
        assertEquals(detailKeys(ann), setOf("phase", "step"))
    }

    @Test(description = "ModifiedLife shape: {delta}")
    fun modifiedLifeDetailKeyShape() {
        val ann = AnnotationBuilder.modifiedLife(1, -3)
        assertEquals(detailKeys(ann), setOf("delta"))
    }

    @Test(description = "ModifiedPower shape: {value}")
    fun modifiedPowerDetailKeyShape() {
        val ann = AnnotationBuilder.modifiedPower(1, 5)
        assertEquals(detailKeys(ann), setOf("value"))
    }

    @Test(description = "ModifiedToughness shape: {value}")
    fun modifiedToughnessDetailKeyShape() {
        val ann = AnnotationBuilder.modifiedToughness(1, 3)
        assertEquals(detailKeys(ann), setOf("value"))
    }

    @Test(description = "LossOfGame shape: {reason}")
    fun lossOfGameDetailKeyShape() {
        val ann = AnnotationBuilder.lossOfGame(1, 0)
        assertEquals(detailKeys(ann), setOf("reason"))
    }

    @Test(description = "CounterAdded shape: {counter_type, transaction_amount}")
    fun counterAddedDetailKeyShape() {
        val ann = AnnotationBuilder.counterAdded(1, "P1P1", 2)
        assertEquals(detailKeys(ann), setOf("counter_type", "transaction_amount"))
    }

    @Test(description = "CounterRemoved shape: {counter_type, transaction_amount}")
    fun counterRemovedDetailKeyShape() {
        val ann = AnnotationBuilder.counterRemoved(1, "LOYALTY", 1)
        assertEquals(detailKeys(ann), setOf("counter_type", "transaction_amount"))
    }

    @Test(description = "Scry shape: {topCount, bottomCount}")
    fun scryDetailKeyShape() {
        val ann = AnnotationBuilder.scry(1, 2, 1)
        assertEquals(detailKeys(ann), setOf("topCount", "bottomCount"))
    }

    @Test(description = "No-detail annotations: NewTurnStarted, SyntheticEvent, EnteredZoneThisTurn, etc.")
    fun noDetailAnnotationShapes() {
        // These annotations carry no detail keys — just type + affected/affector IDs
        assertEquals(detailKeys(AnnotationBuilder.newTurnStarted(1)), emptySet<String>(), "NewTurnStarted")
        assertEquals(detailKeys(AnnotationBuilder.syntheticEvent()), emptySet<String>(), "SyntheticEvent")
        assertEquals(detailKeys(AnnotationBuilder.enteredZoneThisTurn(28, 1)), emptySet<String>(), "EnteredZoneThisTurn")
        assertEquals(detailKeys(AnnotationBuilder.abilityInstanceDeleted(1)), emptySet<String>(), "AbilityInstanceDeleted")
        assertEquals(detailKeys(AnnotationBuilder.tokenCreated(1)), emptySet<String>(), "TokenCreated")
        assertEquals(detailKeys(AnnotationBuilder.tokenDeleted(1)), emptySet<String>(), "TokenDeleted")
        assertEquals(detailKeys(AnnotationBuilder.attachmentCreated(1, 2)), emptySet<String>(), "AttachmentCreated")
        assertEquals(detailKeys(AnnotationBuilder.attachment(1, 2)), emptySet<String>(), "Attachment")
        assertEquals(detailKeys(AnnotationBuilder.removeAttachment(1)), emptySet<String>(), "RemoveAttachment")
        assertEquals(detailKeys(AnnotationBuilder.shuffle(1)), emptySet<String>(), "Shuffle")
        assertEquals(detailKeys(AnnotationBuilder.revealedCardCreated(1)), emptySet<String>(), "RevealedCardCreated")
        assertEquals(detailKeys(AnnotationBuilder.revealedCardDeleted(1)), emptySet<String>(), "RevealedCardDeleted")
    }

    // =======================================================================
    // Golden reference conformance
    //
    // Always-present detail keys from real Arena server, extracted by
    // `just proto-annotation-variance` across 14 proxy sessions / 1898
    // annotation instances. This test fails on ANY change to our builder
    // output vs the golden reference, forcing triage.
    //
    // Workflow after fixing a builder:
    //   1. Fix the builder method in AnnotationBuilder.kt
    //   2. Run `just test-gate` — this test fails
    //   3. Remove the type from expectedMismatch (or update goldenAlwaysKeys)
    //   4. Run `just proto-annotation-variance --summary` to confirm OK
    // =======================================================================

    /**
     * Golden reference: always-present detail keys per annotation type,
     * observed in 100% of instances across all proxy recordings.
     *
     * Source: `just proto-annotation-variance --summary` (2026-02-28,
     * 14 sessions, 1898 instances, 39 types).
     */
    private val goldenAlwaysKeys: Map<String, Set<String>> = mapOf(
        // --- High-frequency types (>50 instances) ---
        "PhaseOrStepModified" to setOf("phase", "step"), // 449 instances, 3 sessions
        "ZoneTransfer" to setOf("category", "zone_dest", "zone_src"), // 181 instances
        "EnteredZoneThisTurn" to emptySet(), // 177 instances — persistent, no details
        "UserActionTaken" to setOf("abilityGrpId", "actionType"), // 153 instances
        "ObjectIdChanged" to setOf("new_id", "orig_id"), // 152 instances
        "TappedUntappedPermanent" to setOf("tapped"), // 148 instances
        "AbilityInstanceCreated" to setOf("source_zone"), // 102 instances
        "AbilityInstanceDeleted" to emptySet(), // 97 instances
        "ManaPaid" to setOf("color", "id"), // 77 instances
        "ResolutionComplete" to setOf("grpid"), // 54 instances
        "ResolutionStart" to setOf("grpid"), // 53 instances

        // --- Medium-frequency types (5-50 instances) ---
        "NewTurnStarted" to emptySet(), // 44 instances
        "DamageDealt" to setOf("damage", "markDamage", "type"), // 12 instances
        "ModifiedToughness" to emptySet(), // 10 instances — all detail keys are optional
        "ModifiedPower" to emptySet(), // 10 instances — all detail keys are optional
        "ModifiedLife" to setOf("life"), // 8 instances
        "SyntheticEvent" to setOf("type"), // 7 instances

        // --- Low-frequency types (1-5 instances) ---
        "TokenCreated" to emptySet(), // 4 instances
        "AttachmentCreated" to emptySet(), // 4 instances
        "Attachment" to emptySet(), // 4 instances
        "CounterAdded" to setOf("counter_type", "transaction_amount"), // 3 instances
        "TokenDeleted" to emptySet(), // 1 instance
    )

    /**
     * Our builder output per type — calls each builder with dummy args,
     * extracts detail keys.
     */
    private val ourBuilderKeys: Map<String, Set<String>> = mapOf(
        "PhaseOrStepModified" to detailKeys(AnnotationBuilder.phaseOrStepModified(1, 1, 2)),
        "ZoneTransfer" to detailKeys(AnnotationBuilder.zoneTransfer(1, 31, 28, "PlayLand")),
        "EnteredZoneThisTurn" to detailKeys(AnnotationBuilder.enteredZoneThisTurn(28, 1)),
        "UserActionTaken" to detailKeys(AnnotationBuilder.userActionTaken(1, 1, 1, 0)),
        "ObjectIdChanged" to detailKeys(AnnotationBuilder.objectIdChanged(1, 2)),
        "TappedUntappedPermanent" to detailKeys(AnnotationBuilder.tappedUntappedPermanent(1, 2)),
        "AbilityInstanceCreated" to detailKeys(AnnotationBuilder.abilityInstanceCreated(1, 31)),
        "AbilityInstanceDeleted" to detailKeys(AnnotationBuilder.abilityInstanceDeleted(1)),
        "ManaPaid" to detailKeys(AnnotationBuilder.manaPaid(1, 1, "Green")),
        "ResolutionComplete" to detailKeys(AnnotationBuilder.resolutionComplete(1, 1)),
        "ResolutionStart" to detailKeys(AnnotationBuilder.resolutionStart(1, 1)),
        "NewTurnStarted" to detailKeys(AnnotationBuilder.newTurnStarted(1)),
        "DamageDealt" to detailKeys(AnnotationBuilder.damageDealt(1, 3)),
        "ModifiedToughness" to detailKeys(AnnotationBuilder.modifiedToughness(1, 3)),
        "ModifiedPower" to detailKeys(AnnotationBuilder.modifiedPower(1, 5)),
        "ModifiedLife" to detailKeys(AnnotationBuilder.modifiedLife(1, -3)),
        "SyntheticEvent" to detailKeys(AnnotationBuilder.syntheticEvent()),
        "TokenCreated" to detailKeys(AnnotationBuilder.tokenCreated(1)),
        "AttachmentCreated" to detailKeys(AnnotationBuilder.attachmentCreated(1, 2)),
        "Attachment" to detailKeys(AnnotationBuilder.attachment(1, 2)),
        "CounterAdded" to detailKeys(AnnotationBuilder.counterAdded(1, "P1P1", 2)),
        "TokenDeleted" to detailKeys(AnnotationBuilder.tokenDeleted(1)),
    )

    /**
     * Known mismatches: types where our builder intentionally differs from
     * the golden reference. Each entry documents WHY and what the fix looks like.
     *
     * When you fix a builder, REMOVE the entry here — the test will confirm
     * the fix by passing without it.
     */
    private val expectedMismatch: Map<String, String> = mapOf(
        // Server sends {life} (absolute life total change), we send {delta} (relative).
        // Fix: rename detail key from "delta" to "life" in modifiedLife().
        "ModifiedLife" to "sends {delta} instead of {life} — rename key",

        // Server sends no always-present keys (effect_id/counter_type are optional),
        // we always send {value}. Client gets P/T from gameObject fields, not annotation.
        // Fix: drop {value} detail entirely, optionally add effect_id/counter_type.
        "ModifiedPower" to "sends {value} — server sends no required keys, drop it",
        "ModifiedToughness" to "sends {value} — server sends no required keys, drop it",

        // Server always sends {type} (value=1), we send no details.
        // Fix: add uint32 detail "type" with value 1 to syntheticEvent().
        "SyntheticEvent" to "missing {type} detail — add it",
    )

    @Test(description = "Golden reference: our builder detail keys match real Arena server always-present keys")
    fun goldenReferenceConformance() {
        val failures = mutableListOf<String>()

        for ((typeName, goldenKeys) in goldenAlwaysKeys) {
            val ourKeys = ourBuilderKeys[typeName]
            if (ourKeys == null) {
                failures += "$typeName: no builder registered in ourBuilderKeys"
                continue
            }

            val missing = goldenKeys - ourKeys // server sends, we don't
            val extra = ourKeys - goldenKeys // we send, server doesn't

            if (missing.isEmpty() && extra.isEmpty()) {
                // OK — check it's NOT in expectedMismatch (stale entry)
                if (typeName in expectedMismatch) {
                    failures += "$typeName: marked as expectedMismatch but now matches! " +
                        "Remove from expectedMismatch."
                }
                continue
            }

            // Mismatch — must be in expectedMismatch
            if (typeName !in expectedMismatch) {
                failures += buildString {
                    append("$typeName: MISMATCH not in expectedMismatch.")
                    if (missing.isNotEmpty()) append(" missing=$missing")
                    if (extra.isNotEmpty()) append(" extra=$extra")
                    append(" Either fix the builder or add to expectedMismatch with a comment.")
                }
            }
        }

        if (failures.isNotEmpty()) {
            val msg = buildString {
                appendLine("Golden reference conformance failures:")
                appendLine()
                for (f in failures) appendLine("  - $f")
                appendLine()
                appendLine("Run `just proto-annotation-variance --summary` for current state.")
            }
            fail(msg)
        }
    }
}
