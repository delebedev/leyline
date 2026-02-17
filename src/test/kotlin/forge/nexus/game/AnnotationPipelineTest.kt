package forge.nexus.game

import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Unit tests for the three-stage annotation pipeline extracted from
 * [StateMapper.buildFromGame]. Tests [StateMapper.annotationsForTransfer]
 * as a pure function — no game engine, no bridge, no card DB.
 *
 * Each test constructs an [StateMapper.AppliedTransfer] and verifies the
 * annotation sequence matches the real Arena server pattern.
 */
@Test
class AnnotationPipelineTest {

    private companion object {
        const val ZONE_STACK = 27
        const val ZONE_BATTLEFIELD = 28
        const val ZONE_EXILE = 29
        const val ZONE_P1_HAND = 31
        const val ZONE_P1_GRAVEYARD = 33
    }

    // --- annotationsForTransfer: PlayLand ---

    @Test
    fun playLandProducesThreeAnnotations() {
        val transfer = StateMapper.AppliedTransfer(
            origId = 100,
            newId = 200,
            category = "PlayLand",
            srcZoneId = ZONE_P1_HAND,
            destZoneId = ZONE_BATTLEFIELD,
            grpId = 12345,
            ownerSeatId = 1,
        )
        val (annotations, persistent) = StateMapper.annotationsForTransfer(transfer, actingSeat = 1)

        assertEquals(annotations.size, 3, "PlayLand should produce 3 annotations")
        assertEquals(annotations[0].typeList.first(), AnnotationType.ObjectIdChanged)
        assertEquals(annotations[1].typeList.first(), AnnotationType.ZoneTransfer_af5a)
        assertEquals(annotations[2].typeList.first(), AnnotationType.UserActionTaken)

        // UserActionTaken should have actionType=3 (Play)
        val actionType = annotations[2].detailsList.first { it.key == "actionType" }
        assertEquals(actionType.getValueInt32(0), 3)
    }

    @Test
    fun playLandHasCorrectIds() {
        val transfer = StateMapper.AppliedTransfer(
            origId = 100,
            newId = 200,
            category = "PlayLand",
            srcZoneId = ZONE_P1_HAND,
            destZoneId = ZONE_BATTLEFIELD,
            grpId = 12345,
            ownerSeatId = 1,
        )
        val (annotations, _) = StateMapper.annotationsForTransfer(transfer, actingSeat = 1)

        // ObjectIdChanged should reference origId in affectedIds
        assertTrue(annotations[0].affectedIdsList.contains(100))
        // ZoneTransfer should reference newId
        assertTrue(annotations[1].affectedIdsList.contains(200))
    }

    @Test
    fun playLandProducesPersistentAnnotation() {
        val transfer = StateMapper.AppliedTransfer(
            origId = 100,
            newId = 200,
            category = "PlayLand",
            srcZoneId = ZONE_P1_HAND,
            destZoneId = ZONE_BATTLEFIELD,
            grpId = 12345,
            ownerSeatId = 1,
        )
        val (_, persistent) = StateMapper.annotationsForTransfer(transfer, actingSeat = 1)

        assertEquals(persistent.size, 1, "Battlefield entry should produce EnteredZoneThisTurn")
        assertEquals(persistent[0].typeList.first(), AnnotationType.EnteredZoneThisTurn)
    }

    // --- annotationsForTransfer: CastSpell ---

    @Test
    fun castSpellProducesSevenAnnotations() {
        val transfer = StateMapper.AppliedTransfer(
            origId = 100,
            newId = 200,
            category = "CastSpell",
            srcZoneId = ZONE_P1_HAND,
            destZoneId = ZONE_STACK,
            grpId = 67890,
            ownerSeatId = 1,
        )
        val (annotations, persistent) = StateMapper.annotationsForTransfer(transfer, actingSeat = 1)

        assertEquals(annotations.size, 7, "CastSpell should produce 7 annotations")
        assertEquals(annotations[0].typeList.first(), AnnotationType.ObjectIdChanged)
        assertEquals(annotations[1].typeList.first(), AnnotationType.ZoneTransfer_af5a)
        assertEquals(annotations[2].typeList.first(), AnnotationType.AbilityInstanceCreated)
        assertEquals(annotations[3].typeList.first(), AnnotationType.TappedUntappedPermanent)
        assertEquals(annotations[4].typeList.first(), AnnotationType.ManaPaid)
        assertEquals(annotations[5].typeList.first(), AnnotationType.AbilityInstanceDeleted)
        assertEquals(annotations[6].typeList.first(), AnnotationType.UserActionTaken)

        // Stack is not battlefield — no persistent annotation
        assertTrue(persistent.isEmpty())
    }

    @Test
    fun castSpellUserActionIsCast() {
        val transfer = StateMapper.AppliedTransfer(
            origId = 100,
            newId = 200,
            category = "CastSpell",
            srcZoneId = ZONE_P1_HAND,
            destZoneId = ZONE_STACK,
            grpId = 67890,
            ownerSeatId = 1,
        )
        val (annotations, _) = StateMapper.annotationsForTransfer(transfer, actingSeat = 1)

        val actionType = annotations[6].detailsList.first { it.key == "actionType" }
        assertEquals(actionType.getValueInt32(0), 1, "actionType should be 1 (Cast)")
    }

    // --- annotationsForTransfer: Resolve ---

    @Test
    fun resolveProducesThreeAnnotations() {
        val transfer = StateMapper.AppliedTransfer(
            origId = 200,
            newId = 200,
            category = "Resolve",
            srcZoneId = ZONE_STACK,
            destZoneId = ZONE_BATTLEFIELD,
            grpId = 67890,
            ownerSeatId = 1,
        )
        val (annotations, persistent) = StateMapper.annotationsForTransfer(transfer, actingSeat = 1)

        assertEquals(annotations.size, 3, "Resolve should produce 3 annotations")
        assertEquals(annotations[0].typeList.first(), AnnotationType.ResolutionStart)
        assertEquals(annotations[1].typeList.first(), AnnotationType.ResolutionComplete)
        assertEquals(annotations[2].typeList.first(), AnnotationType.ZoneTransfer_af5a)

        // Lands on battlefield — persistent annotation
        assertEquals(persistent.size, 1)
    }

    @Test
    fun resolveZoneTransferHasActingSeat() {
        val transfer = StateMapper.AppliedTransfer(
            origId = 200,
            newId = 200,
            category = "Resolve",
            srcZoneId = ZONE_STACK,
            destZoneId = ZONE_BATTLEFIELD,
            grpId = 67890,
            ownerSeatId = 1,
        )
        val (annotations, _) = StateMapper.annotationsForTransfer(transfer, actingSeat = 2)

        // Resolve ZoneTransfer should carry actingSeat as affectorId
        assertEquals(annotations[2].affectorId, 2)
    }

    @Test
    fun resolveUsesGrpId() {
        val transfer = StateMapper.AppliedTransfer(
            origId = 200,
            newId = 200,
            category = "Resolve",
            srcZoneId = ZONE_STACK,
            destZoneId = ZONE_BATTLEFIELD,
            grpId = 67890,
            ownerSeatId = 1,
        )
        val (annotations, _) = StateMapper.annotationsForTransfer(transfer, actingSeat = 1)

        val grpid = annotations[0].detailsList.first { it.key == "grpid" }
        assertEquals(grpid.getValueUint32(0), 67890)
    }

    // --- Edge cases ---

    @Test
    fun unknownCategoryProducesNoAnnotations() {
        val transfer = StateMapper.AppliedTransfer(
            origId = 100,
            newId = 200,
            category = "ZoneTransfer",
            srcZoneId = ZONE_EXILE,
            destZoneId = ZONE_P1_GRAVEYARD,
            grpId = 0,
            ownerSeatId = 1,
        )
        val (annotations, persistent) = StateMapper.annotationsForTransfer(transfer, actingSeat = 1)

        assertTrue(annotations.isEmpty(), "Unknown category should produce no annotations")
        assertTrue(persistent.isEmpty(), "Graveyard dest should produce no persistent annotation")
    }

    @Test
    fun noBattlefieldDestNoPersistentAnnotation() {
        val transfer = StateMapper.AppliedTransfer(
            origId = 100,
            newId = 200,
            category = "CastSpell",
            srcZoneId = ZONE_P1_HAND,
            destZoneId = ZONE_STACK,
            grpId = 67890,
            ownerSeatId = 1,
        )
        val (_, persistent) = StateMapper.annotationsForTransfer(transfer, actingSeat = 1)
        assertTrue(persistent.isEmpty(), "Stack dest should not produce EnteredZoneThisTurn")
    }

    @Test
    fun resolveToGraveyardNoPersistentAnnotation() {
        // Spell resolves but goes to graveyard (instant/sorcery)
        val transfer = StateMapper.AppliedTransfer(
            origId = 200,
            newId = 200,
            category = "Resolve",
            srcZoneId = ZONE_STACK,
            destZoneId = ZONE_P1_GRAVEYARD,
            grpId = 67890,
            ownerSeatId = 1,
        )
        val (annotations, persistent) = StateMapper.annotationsForTransfer(transfer, actingSeat = 1)

        assertEquals(annotations.size, 3, "Resolve still produces 3 annotations")
        assertTrue(persistent.isEmpty(), "Graveyard dest should not produce EnteredZoneThisTurn")
    }
}
