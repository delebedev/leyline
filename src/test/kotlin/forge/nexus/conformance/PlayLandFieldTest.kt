package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import forge.nexus.game.CardDb
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType

/**
 * Field-level integration test for play-land protocol messages.
 *
 * Starts a deterministic game, plays a land, then asserts every field
 * on the resulting GameStateMessage matches real Arena recordings:
 *
 * - Annotation IDs: sequential, non-zero, monotonically increasing
 * - affectorId / affectedIds: carry correct game-context instanceIds
 * - ZoneTransfer details: use typed int32 values (not strings) for zone IDs
 * - ObjectIdChanged details: include orig_id / new_id
 * - UserActionTaken details: include actionType + abilityGrpId
 * - prevGameStateId: references the prior state
 * - persistentAnnotations: EnteredZoneThisTurn for the newly-played land
 *
 * All discovered from real Arena recordings via proto-trace / proto-inspect.
 * These assertions are expected to FAIL until the implementation catches up (TDD).
 */
@Test(groups = ["integration", "conformance"])
class PlayLandFieldTest : ConformanceTestBase() {

    private companion object {
        const val ZONE_BATTLEFIELD = 28
        const val ZONE_P1_HAND = 31
    }

    @Test(description = "Play land: annotation IDs are sequential, non-zero, monotonically increasing")
    fun annotationIdsAreSequential() {
        val gsm = playLandAndCapture() ?: return

        val ids = gsm.annotationsList.map { it.id }
        assertTrue(ids.isNotEmpty(), "Should have annotations")
        assertTrue(ids.all { it > 0u.toInt() }, "All annotation IDs should be > 0, got: $ids")
        assertEquals(ids, ids.sorted(), "Annotation IDs should be monotonically increasing, got: $ids")
        assertEquals(ids.toSet().size, ids.size, "Annotation IDs should be unique, got: $ids")
    }

    @Test(description = "Play land: ZoneTransfer has affectedIds=[land], typed zone details, category=PlayLand")
    fun zoneTransferAnnotationFields() {
        val (gsm, landInstanceId) = playLandAndCaptureWithId() ?: return

        val zt = gsm.annotationsList.firstOrNull { AnnotationType.ZoneTransfer_af5a in it.typeList }
        assertTrue(zt != null, "Should have ZoneTransfer annotation")
        zt!!

        // affectedIds should reference the land
        assertTrue(zt.affectedIdsList.contains(landInstanceId), "ZoneTransfer affectedIds should contain land instanceId $landInstanceId, got: ${zt.affectedIdsList}")

        // zone_src detail: typed int32, value = hand zone
        val zoneSrc = zt.detailsList.firstOrNull { it.key == "zone_src" }
        assertTrue(zoneSrc != null, "ZoneTransfer should have zone_src detail")
        assertEquals(zoneSrc!!.type, KeyValuePairValueType.Int32, "zone_src should use Int32 type, got: ${zoneSrc.type}")
        assertEquals(zoneSrc.getValueInt32(0), ZONE_P1_HAND, "zone_src should be hand zone ($ZONE_P1_HAND), got: ${zoneSrc.getValueInt32(0)}")

        // zone_dest detail: typed int32, value = battlefield
        val zoneDest = zt.detailsList.firstOrNull { it.key == "zone_dest" }
        assertTrue(zoneDest != null, "ZoneTransfer should have zone_dest detail")
        assertEquals(zoneDest!!.type, KeyValuePairValueType.Int32, "zone_dest should use Int32 type, got: ${zoneDest.type}")
        assertEquals(zoneDest.getValueInt32(0), ZONE_BATTLEFIELD, "zone_dest should be battlefield ($ZONE_BATTLEFIELD), got: ${zoneDest.getValueInt32(0)}")

        // category detail: typed String, value = "PlayLand"
        val category = zt.detailsList.firstOrNull { it.key == "category" }
        assertTrue(category != null, "ZoneTransfer should have category detail")
        @Suppress("EnumValuesSoftDeprecate")
        assertEquals(category!!.type, KeyValuePairValueType.String, "category should use String type")
        assertEquals(category.getValueString(0), "PlayLand", "category should be PlayLand")
    }

    @Test(description = "Play land: ObjectIdChanged has orig_id/new_id details with typed int32 values")
    fun objectIdChangedDetails() {
        val (gsm, landInstanceId) = playLandAndCaptureWithId() ?: return

        val oic = gsm.annotationsList.firstOrNull { AnnotationType.ObjectIdChanged in it.typeList }
        assertTrue(oic != null, "Should have ObjectIdChanged annotation")
        oic!!

        assertTrue(oic.affectedIdsList.contains(landInstanceId), "ObjectIdChanged affectedIds should contain land instanceId $landInstanceId")

        // orig_id detail
        val origId = oic.detailsList.firstOrNull { it.key == "orig_id" }
        assertTrue(origId != null, "ObjectIdChanged should have orig_id detail")
        assertEquals(origId!!.type, KeyValuePairValueType.Int32, "orig_id should use Int32 type")
        assertTrue(origId.valueInt32Count > 0, "orig_id should have a value")

        // new_id detail
        val newId = oic.detailsList.firstOrNull { it.key == "new_id" }
        assertTrue(newId != null, "ObjectIdChanged should have new_id detail")
        assertEquals(newId!!.type, KeyValuePairValueType.Int32, "new_id should use Int32 type")
        assertTrue(newId.valueInt32Count > 0, "new_id should have a value")

        // new_id should match the land's current instanceId
        assertEquals(newId.getValueInt32(0), landInstanceId, "new_id should equal land's instanceId after move")
    }

    @Test(description = "Play land: UserActionTaken has affectorId=seatId, actionType + abilityGrpId details")
    fun userActionTakenFields() {
        val (gsm, landInstanceId) = playLandAndCaptureWithId() ?: return

        val uat = gsm.annotationsList.firstOrNull { AnnotationType.UserActionTaken in it.typeList }
        assertTrue(uat != null, "Should have UserActionTaken annotation")
        uat!!

        // affectorId = the acting player's seat (1 for human)
        assertEquals(uat.affectorId.toInt(), 1, "UserActionTaken affectorId should be seat 1 (human player), got: ${uat.affectorId}")

        // affectedIds should reference the land
        assertTrue(uat.affectedIdsList.contains(landInstanceId), "UserActionTaken affectedIds should contain land instanceId $landInstanceId")

        // actionType detail (Play = a specific enum value)
        val actionType = uat.detailsList.firstOrNull { it.key == "actionType" }
        assertTrue(actionType != null, "UserActionTaken should have actionType detail")
        assertEquals(actionType!!.type, KeyValuePairValueType.Int32, "actionType should use Int32 type")
        assertTrue(actionType.valueInt32Count > 0, "actionType should have a value")

        // abilityGrpId detail (0 for land play — no ability involved)
        val abilityGrpId = uat.detailsList.firstOrNull { it.key == "abilityGrpId" }
        assertTrue(abilityGrpId != null, "UserActionTaken should have abilityGrpId detail")
        assertEquals(abilityGrpId!!.type, KeyValuePairValueType.Int32, "abilityGrpId should use Int32 type")
    }

    @Test(description = "Play land: GSM has prevGameStateId referencing prior state")
    fun prevGameStateIdPresent() {
        val gsm = playLandAndCapture() ?: return

        assertNotEquals(gsm.prevGameStateId.toInt(), 0, "GSM should have prevGameStateId set (non-zero)")
        assertTrue(gsm.prevGameStateId < gsm.gameStateId, "prevGameStateId (${gsm.prevGameStateId}) should be less than gameStateId (${gsm.gameStateId})")
    }

    @Test(description = "Play land: persistentAnnotations includes EnteredZoneThisTurn for the land")
    fun persistentAnnotationsPresent() {
        val (gsm, landInstanceId) = playLandAndCaptureWithId() ?: return

        assertTrue(gsm.persistentAnnotationsCount > 0, "GSM should have persistentAnnotations after playing a land")

        val enteredZone = gsm.persistentAnnotationsList.firstOrNull {
            AnnotationType.EnteredZoneThisTurn in it.typeList
        }
        assertTrue(enteredZone != null, "Should have EnteredZoneThisTurn persistent annotation")
        assertTrue(
            enteredZone!!.affectedIdsList.contains(landInstanceId),
            "EnteredZoneThisTurn should reference the land instanceId $landInstanceId, got: ${enteredZone.affectedIdsList}",
        )
    }

    @Test(description = "Play land: land gameObject on battlefield has uniqueAbilities (mana ability)")
    fun landHasUniqueAbilities() {
        val (gsm, landInstanceId) = playLandAndCaptureWithId() ?: return

        val landObj = gsm.gameObjectsList.firstOrNull { it.instanceId == landInstanceId }
        assertTrue(landObj != null, "GSM should have gameObject for the played land")
        landObj!!

        assertEquals(landObj.zoneId, ZONE_BATTLEFIELD, "Land should be on battlefield")
        assertTrue(landObj.uniqueAbilitiesCount > 0, "Land gameObject should have uniqueAbilities (mana ability), got 0")
    }

    // --- helpers ---

    private fun playLandAndCapture(): GameStateMessage? {
        val (b, game, gsId) = startGameAtMain1()
        playLand(b) ?: return null

        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)
        val gsmMsg = result.messages.firstOrNull { it.hasGameStateMessage() } ?: return null
        return gsmMsg.gameStateMessage
    }

    private fun playLandAndCaptureWithId(): Pair<GameStateMessage, Int>? {
        val (b, game, gsId) = startGameAtMain1()

        // Find the land before playing it so we can track its instanceId
        val player = b.getPlayer(1) ?: return null
        val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: return null
        val landInstanceId = b.getOrAllocInstanceId(land.id)
        val landName = land.name
        val grpId = CardDb.lookupByName(landName) ?: 0

        playLand(b) ?: return null

        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)
        val gsmMsg = result.messages.firstOrNull { it.hasGameStateMessage() } ?: return null

        return gsmMsg.gameStateMessage to landInstanceId
    }
}
