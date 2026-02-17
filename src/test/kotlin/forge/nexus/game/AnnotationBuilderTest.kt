package forge.nexus.game

import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType

@Test
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
    fun phaseOrStepModifiedMinimal() {
        val ann = AnnotationBuilder.phaseOrStepModified()
        assertTrue(ann.typeList.contains(AnnotationType.PhaseOrStepModified))
        assertEquals(ann.affectorId, 0, "No affectorId for phase change")
        assertEquals(ann.affectedIdsCount, 0, "No affectedIds for phase change")
    }

    // --- ManaPaid ---

    @Test
    fun manaPaidFields() {
        val ann = AnnotationBuilder.manaPaid(instanceId = 600)
        assertTrue(ann.typeList.contains(AnnotationType.ManaPaid))
        assertTrue(ann.affectedIdsList.contains(600))
        assertEquals(ann.affectorId, 0, "ManaPaid has no affectorId")
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

    // --- AbilityInstanceCreated ---

    @Test
    fun abilityInstanceCreatedFields() {
        val ann = AnnotationBuilder.abilityInstanceCreated(instanceId = 900)
        assertTrue(ann.typeList.contains(AnnotationType.AbilityInstanceCreated))
        assertTrue(ann.affectedIdsList.contains(900))
        assertEquals(ann.affectorId, 0, "No affectorId")
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
        val ann = AnnotationBuilder.enteredZoneThisTurn(zoneId = 28, 100, 200)
        assertTrue(ann.typeList.contains(AnnotationType.EnteredZoneThisTurn))
        assertEquals(ann.affectorId, 28, "affectorId should be the zone ID")
        assertTrue(ann.affectedIdsList.contains(100))
        assertTrue(ann.affectedIdsList.contains(200))
        assertEquals(ann.affectedIdsCount, 2, "Should have 2 affected IDs")
    }

    @Test
    fun enteredZoneThisTurnSingleId() {
        val ann = AnnotationBuilder.enteredZoneThisTurn(zoneId = 28, 100)
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
}
