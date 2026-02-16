package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

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
        const val ZONE_LIMBO = 30
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
        val (gsm, _, newInstanceId) = playLandAndCaptureWithIds() ?: return

        val zt = gsm.annotationsList.firstOrNull { AnnotationType.ZoneTransfer_af5a in it.typeList }
        assertTrue(zt != null, "Should have ZoneTransfer annotation")
        zt!!

        // affectedIds should reference the new instanceId (post zone-transfer)
        assertTrue(zt.affectedIdsList.contains(newInstanceId), "ZoneTransfer affectedIds should contain land instanceId $newInstanceId, got: ${zt.affectedIdsList}")

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
        val (gsm, origInstanceId, newInstanceId) = playLandAndCaptureWithIds() ?: return

        val oic = gsm.annotationsList.firstOrNull { AnnotationType.ObjectIdChanged in it.typeList }
        assertTrue(oic != null, "Should have ObjectIdChanged annotation")
        oic!!

        // affectedIds = orig (pre-move) instanceId
        assertTrue(oic.affectedIdsList.contains(origInstanceId), "ObjectIdChanged affectedIds should contain orig instanceId $origInstanceId")

        // orig_id detail = pre-move ID
        val origIdDetail = oic.detailsList.firstOrNull { it.key == "orig_id" }
        assertTrue(origIdDetail != null, "ObjectIdChanged should have orig_id detail")
        assertEquals(origIdDetail!!.type, KeyValuePairValueType.Int32, "orig_id should use Int32 type")
        assertEquals(origIdDetail.getValueInt32(0), origInstanceId, "orig_id should equal pre-move instanceId")

        // new_id detail = post-move ID
        val newIdDetail = oic.detailsList.firstOrNull { it.key == "new_id" }
        assertTrue(newIdDetail != null, "ObjectIdChanged should have new_id detail")
        assertEquals(newIdDetail!!.type, KeyValuePairValueType.Int32, "new_id should use Int32 type")
        assertEquals(newIdDetail.getValueInt32(0), newInstanceId, "new_id should equal post-move instanceId")

        // orig and new must differ (real server always allocates new ID on zone transfer)
        assertNotEquals(origInstanceId, newInstanceId, "orig_id and new_id should differ after zone transfer")
    }

    @Test(description = "Play land: UserActionTaken has affectorId=seatId, actionType + abilityGrpId details")
    fun userActionTakenFields() {
        val (gsm, _, landInstanceId) = playLandAndCaptureWithIds() ?: return

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
        val (gsm, _, landInstanceId) = playLandAndCaptureWithIds() ?: return

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
        val (gsm, _, landInstanceId) = playLandAndCaptureWithIds() ?: return

        val landObj = gsm.gameObjectsList.firstOrNull { it.instanceId == landInstanceId }
        assertTrue(landObj != null, "GSM should have gameObject for the played land")
        landObj!!

        assertEquals(landObj.zoneId, ZONE_BATTLEFIELD, "Land should be on battlefield")
        assertTrue(landObj.uniqueAbilitiesCount > 0, "Land gameObject should have uniqueAbilities (mana ability), got 0")
    }

    @Test(description = "Play land: old instanceId retired to Limbo with gameObject")
    fun oldInstanceRetiredToLimbo() {
        val (gsm, origInstanceId, newInstanceId) = playLandAndCaptureWithIds() ?: return

        // Old instanceId should NOT be the same as new
        assertNotEquals(origInstanceId, newInstanceId, "Zone transfer must allocate a new instanceId")

        // Limbo zone should contain the old instanceId
        val limboZone = gsm.zonesList.firstOrNull { it.type == ZoneType.Limbo }
        assertTrue(limboZone != null, "GSM should have Limbo zone")
        assertTrue(
            limboZone!!.objectInstanceIdsList.contains(origInstanceId),
            "Limbo zone should contain old instanceId $origInstanceId, got: ${limboZone.objectInstanceIdsList}",
        )

        // Real server sends a Limbo gameObject to the owner seat (Private, viewers=[owner]).
        // This moves the old object from Hand→Limbo in the client's accumulated state.
        // Without it, the client still has the old object in Hand and shows a "jump back" artifact.
        val limboObj = gsm.gameObjectsList.firstOrNull { it.instanceId == origInstanceId }
        assertTrue(limboObj != null, "Should send Limbo gameObject for retired instanceId $origInstanceId")
        assertEquals(limboObj!!.zoneId, ZONE_LIMBO, "Limbo gameObject should have zoneId=Limbo")
        assertEquals(limboObj.visibility, Visibility.Private, "Limbo gameObject should be Private")

        // New instanceId should be on battlefield (not in Limbo)
        val newObj = gsm.gameObjectsList.firstOrNull { it.instanceId == newInstanceId }
        assertTrue(newObj != null, "GSM should have gameObject for new instanceId $newInstanceId")
        assertEquals(newObj!!.zoneId, ZONE_BATTLEFIELD, "New object should be on battlefield")

        // diffDeletedInstanceIds should NOT contain the old ID immediately.
        // Real server defers this (gs=63, not gs=10). The Limbo gameObject handles it.
        assertTrue(
            !gsm.diffDeletedInstanceIdsList.contains(origInstanceId),
            "diffDeletedInstanceIds should NOT contain origId immediately, got: ${gsm.diffDeletedInstanceIdsList}",
        )
    }

    @Test(description = "Play land: accumulated client state has new object on BF, old removed")
    fun accumulatedStateAfterPlayLand() {
        val (b, game, gsId) = startGameAtMain1()

        // Accumulate game-start + snapshot (matches MatchSession flow)
        val startResult = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)
        val acc = ClientAccumulator()
        acc.processAll(startResult.messages)
        b.snapshotState(game)

        // Capture pre-play IDs
        val player = b.getPlayer(1) ?: return
        val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: return
        val origInstanceId = b.getOrAllocInstanceId(land.id)
        val forgeCardId = land.id

        // Play land
        playLand(b) ?: return
        val postResult = BundleBuilder.postAction(game, b, "test-match", 1, startResult.nextMsgId, startResult.nextGsId)
        acc.processAll(postResult.messages)
        val newInstanceId = b.getOrAllocInstanceId(forgeCardId)

        // New instanceId should be in accumulated objects on BF
        val newObj = acc.objects[newInstanceId]
        assertTrue(newObj != null, "Accumulated objects should have new instanceId $newInstanceId")
        assertEquals(newObj!!.zoneId, ZONE_BATTLEFIELD, "New object should be on battlefield")

        // Old instanceId: should be in Limbo (gameObject present, not deleted).
        // Real server sends Limbo gameObject to owner — old object updated to Limbo, not deleted.
        val oldObj = acc.objects[origInstanceId]
        assertTrue(oldObj != null, "Old instanceId $origInstanceId should still be in accumulated objects (as Limbo gameObject)")
        assertEquals(oldObj!!.zoneId, ZONE_LIMBO, "Old object should be in Limbo, not ${oldObj.zoneId}")
        // Either way, old ID must NOT be in the Hand zone
        val handZone = acc.zones.values.firstOrNull { it.type == ZoneType.Hand && it.ownerSeatId == 1 }
        assertTrue(handZone != null, "Should have P1 hand zone")
        assertTrue(
            !handZone!!.objectInstanceIdsList.contains(origInstanceId),
            "Old instanceId $origInstanceId should NOT be in hand zone after play, got: ${handZone.objectInstanceIdsList}",
        )

        // BF zone should contain new instanceId
        val bfZone = acc.zones[ZONE_BATTLEFIELD]
        assertTrue(bfZone != null, "Should have battlefield zone")
        assertTrue(
            bfZone!!.objectInstanceIdsList.contains(newInstanceId),
            "Battlefield should contain new instanceId $newInstanceId, got: ${bfZone.objectInstanceIdsList}",
        )

        // Limbo zone should contain old instanceId
        val limboZone = acc.zones[ZONE_LIMBO]
        assertTrue(limboZone != null, "Should have Limbo zone")
        assertTrue(
            limboZone!!.objectInstanceIdsList.contains(origInstanceId),
            "Limbo should contain old instanceId $origInstanceId, got: ${limboZone.objectInstanceIdsList}",
        )

        // All action instanceIds should exist in accumulated objects
        val missing = acc.actionInstanceIdsMissingFromObjects()
        assertTrue(missing.isEmpty(), "Action instanceIds missing from objects: $missing")

        // All visible zone refs should be valid
        val missingZoneObjs = acc.zoneObjectsMissingFromObjects()
        assertTrue(missingZoneObjs.isEmpty(), "Zone objects missing from objects: $missingZoneObjs")
    }

    // --- helpers ---

    private fun playLandAndCapture(): GameStateMessage? {
        val (b, game, gsId) = startGameAtMain1()
        playLand(b) ?: return null

        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)
        val gsmMsg = result.messages.firstOrNull { it.hasGameStateMessage() } ?: return null
        return gsmMsg.gameStateMessage
    }

    /**
     * Returns (gsm, origInstanceId, newInstanceId).
     * origInstanceId = pre-play ID (used in ObjectIdChanged.affectedIds/orig_id).
     * newInstanceId = post-play ID (used in ZoneTransfer, UserActionTaken, gameObjects).
     */
    private fun playLandAndCaptureWithIds(): Triple<GameStateMessage, Int, Int>? {
        val (b, game, gsId) = startGameAtMain1()

        // Capture pre-play instanceId
        val player = b.getPlayer(1) ?: return null
        val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: return null
        val origInstanceId = b.getOrAllocInstanceId(land.id)
        val forgeCardId = land.id

        playLand(b) ?: return null

        // postAction triggers buildFromGame → reallocInstanceId on zone transfer
        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)
        val gsmMsg = result.messages.firstOrNull { it.hasGameStateMessage() } ?: return null

        // After postAction, forward map points to the new ID
        val newInstanceId = b.getOrAllocInstanceId(forgeCardId)

        return Triple(gsmMsg.gameStateMessage, origInstanceId, newInstanceId)
    }
}
