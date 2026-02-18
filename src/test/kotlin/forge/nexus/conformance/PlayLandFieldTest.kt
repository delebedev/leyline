package forge.nexus.conformance

import forge.nexus.game.ZoneIds
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

/**
 * Field-level integration test for play-land protocol messages.
 *
 * Starts a deterministic game, plays a land, then asserts every field
 * on the resulting GameStateMessage matches real Arena recordings.
 */
@Test(groups = ["integration", "conformance"])
class PlayLandFieldTest : ConformanceTestBase() {

    @Test(description = "Play land: annotation IDs are sequential, non-zero, monotonically increasing")
    fun annotationIdsAreSequential() {
        val gsm = playLandAndCapture() ?: error("No land in hand at seed 42")

        val ids = gsm.annotationsList.map { it.id }
        assertTrue(ids.isNotEmpty(), "Should have annotations")
        assertTrue(ids.all { it > 0u.toInt() }, "All annotation IDs should be > 0, got: $ids")
        assertEquals(ids, ids.sorted(), "Annotation IDs should be monotonically increasing, got: $ids")
        assertEquals(ids.toSet().size, ids.size, "Annotation IDs should be unique, got: $ids")
    }

    @Test(description = "Play land: ZoneTransfer has affectedIds=[land], typed zone details, category=PlayLand")
    fun zoneTransferAnnotationFields() {
        val (gsm, _, newInstanceId) = playLandAndCaptureWithIds() ?: error("No land in hand at seed 42")

        val zt = gsm.annotationOrNull(AnnotationType.ZoneTransfer_af5a)
        assertTrue(zt != null, "Should have ZoneTransfer annotation")
        zt!!

        assertTrue(zt.affectedIdsList.contains(newInstanceId), "ZoneTransfer affectedIds should contain land instanceId $newInstanceId, got: ${zt.affectedIdsList}")

        val zoneSrc = zt.detail("zone_src")
        assertTrue(zoneSrc != null, "ZoneTransfer should have zone_src detail")
        assertEquals(zoneSrc!!.type, KeyValuePairValueType.Int32, "zone_src should use Int32 type, got: ${zoneSrc.type}")
        assertEquals(zoneSrc.getValueInt32(0), ZoneIds.P1_HAND, "zone_src should be hand zone (${ZoneIds.P1_HAND}), got: ${zoneSrc.getValueInt32(0)}")

        val zoneDest = zt.detail("zone_dest")
        assertTrue(zoneDest != null, "ZoneTransfer should have zone_dest detail")
        assertEquals(zoneDest!!.type, KeyValuePairValueType.Int32, "zone_dest should use Int32 type, got: ${zoneDest.type}")
        assertEquals(zoneDest.getValueInt32(0), ZoneIds.BATTLEFIELD, "zone_dest should be battlefield (${ZoneIds.BATTLEFIELD}), got: ${zoneDest.getValueInt32(0)}")

        val category = zt.detail("category")
        assertTrue(category != null, "ZoneTransfer should have category detail")
        @Suppress("EnumValuesSoftDeprecate")
        assertEquals(category!!.type, KeyValuePairValueType.String, "category should use String type")
        assertEquals(category.getValueString(0), "PlayLand", "category should be PlayLand")
    }

    @Test(description = "Play land: ObjectIdChanged has orig_id/new_id details with typed int32 values")
    fun objectIdChangedDetails() {
        val (gsm, origInstanceId, newInstanceId) = playLandAndCaptureWithIds() ?: error("No land in hand at seed 42")

        val oic = gsm.annotationOrNull(AnnotationType.ObjectIdChanged)
        assertTrue(oic != null, "Should have ObjectIdChanged annotation")
        oic!!

        assertTrue(oic.affectedIdsList.contains(origInstanceId), "ObjectIdChanged affectedIds should contain orig instanceId $origInstanceId")

        val origIdDetail = oic.detail("orig_id")
        assertTrue(origIdDetail != null, "ObjectIdChanged should have orig_id detail")
        assertEquals(origIdDetail!!.type, KeyValuePairValueType.Int32, "orig_id should use Int32 type")
        assertEquals(origIdDetail.getValueInt32(0), origInstanceId, "orig_id should equal pre-move instanceId")

        val newIdDetail = oic.detail("new_id")
        assertTrue(newIdDetail != null, "ObjectIdChanged should have new_id detail")
        assertEquals(newIdDetail!!.type, KeyValuePairValueType.Int32, "new_id should use Int32 type")
        assertEquals(newIdDetail.getValueInt32(0), newInstanceId, "new_id should equal post-move instanceId")

        assertNotEquals(origInstanceId, newInstanceId, "orig_id and new_id should differ after zone transfer")
    }

    @Test(description = "Play land: UserActionTaken has affectorId=seatId, actionType + abilityGrpId details")
    fun userActionTakenFields() {
        val (gsm, _, landInstanceId) = playLandAndCaptureWithIds() ?: error("No land in hand at seed 42")

        val uat = gsm.annotationOrNull(AnnotationType.UserActionTaken)
        assertTrue(uat != null, "Should have UserActionTaken annotation")
        uat!!

        assertEquals(uat.affectorId.toInt(), 1, "UserActionTaken affectorId should be seat 1 (human player), got: ${uat.affectorId}")
        assertTrue(uat.affectedIdsList.contains(landInstanceId), "UserActionTaken affectedIds should contain land instanceId $landInstanceId")

        val actionType = uat.detail("actionType")
        assertTrue(actionType != null, "UserActionTaken should have actionType detail")
        assertEquals(actionType!!.type, KeyValuePairValueType.Int32, "actionType should use Int32 type")
        assertTrue(actionType.valueInt32Count > 0, "actionType should have a value")

        val abilityGrpId = uat.detail("abilityGrpId")
        assertTrue(abilityGrpId != null, "UserActionTaken should have abilityGrpId detail")
        assertEquals(abilityGrpId!!.type, KeyValuePairValueType.Int32, "abilityGrpId should use Int32 type")
    }

    @Test(description = "Play land: GSM has prevGameStateId referencing prior state")
    fun prevGameStateIdPresent() {
        val gsm = playLandAndCapture() ?: error("No land in hand at seed 42")

        assertNotEquals(gsm.prevGameStateId.toInt(), 0, "GSM should have prevGameStateId set (non-zero)")
        assertTrue(gsm.prevGameStateId < gsm.gameStateId, "prevGameStateId (${gsm.prevGameStateId}) should be less than gameStateId (${gsm.gameStateId})")
    }

    @Test(description = "Play land: persistentAnnotations includes EnteredZoneThisTurn for the land")
    fun persistentAnnotationsPresent() {
        val (gsm, _, landInstanceId) = playLandAndCaptureWithIds() ?: error("No land in hand at seed 42")

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
        val (gsm, _, landInstanceId) = playLandAndCaptureWithIds() ?: error("No land in hand at seed 42")

        val landObj = gsm.gameObjectsList.firstOrNull { it.instanceId == landInstanceId }
        assertTrue(landObj != null, "GSM should have gameObject for the played land")
        landObj!!

        assertEquals(landObj.zoneId, ZoneIds.BATTLEFIELD, "Land should be on battlefield")
        assertTrue(landObj.uniqueAbilitiesCount > 0, "Land gameObject should have uniqueAbilities (mana ability), got 0")
    }

    @Test(description = "Play land: old instanceId retired to Limbo with gameObject")
    fun oldInstanceRetiredToLimbo() {
        val (gsm, origInstanceId, newInstanceId) = playLandAndCaptureWithIds() ?: error("No land in hand at seed 42")

        assertNotEquals(origInstanceId, newInstanceId, "Zone transfer must allocate a new instanceId")

        // Limbo zone + gameObject for old instanceId
        assertLimboContains(gsm, origInstanceId)

        // New instanceId should be on battlefield (not in Limbo)
        val newObj = gsm.gameObjectsList.firstOrNull { it.instanceId == newInstanceId }
        assertTrue(newObj != null, "GSM should have gameObject for new instanceId $newInstanceId")
        assertEquals(newObj!!.zoneId, ZoneIds.BATTLEFIELD, "New object should be on battlefield")

        // diffDeletedInstanceIds should NOT contain the old ID immediately
        assertTrue(
            !gsm.diffDeletedInstanceIdsList.contains(origInstanceId),
            "diffDeletedInstanceIds should NOT contain origId immediately, got: ${gsm.diffDeletedInstanceIdsList}",
        )
    }

    @Test(description = "Play land: accumulated client state has new object on BF, old removed")
    fun accumulatedStateAfterPlayLand() {
        val (b, game, gsId) = startGameAtMain1()

        val startResult = gameStart(game, b, 1, gsId)
        val acc = ClientAccumulator()
        acc.processAll(startResult.messages)
        b.snapshotState(game)

        val player = b.getPlayer(1) ?: error("Player 1 not found")
        val land = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isLand } ?: error("No land in hand at seed 42")
        val origInstanceId = b.getOrAllocInstanceId(land.id)
        val forgeCardId = land.id

        playLand(b) ?: error("No land in hand at seed 42")
        val postResult = postAction(game, b, startResult.nextMsgId, startResult.nextGsId)
        acc.processAll(postResult.messages)
        val newInstanceId = b.getOrAllocInstanceId(forgeCardId)

        // New instanceId on BF
        val newObj = acc.objects[newInstanceId]
        assertTrue(newObj != null, "Accumulated objects should have new instanceId $newInstanceId")
        assertEquals(newObj!!.zoneId, ZoneIds.BATTLEFIELD, "New object should be on battlefield")

        // Old instanceId in Limbo
        val oldObj = acc.objects[origInstanceId]
        assertTrue(oldObj != null, "Old instanceId $origInstanceId should still be in accumulated objects (as Limbo gameObject)")
        assertEquals(oldObj!!.zoneId, ZoneIds.LIMBO, "Old object should be in Limbo, not ${oldObj.zoneId}")

        val handZone = acc.zones.values.firstOrNull { it.type == ZoneType.Hand && it.ownerSeatId == 1 }
        assertTrue(handZone != null, "Should have P1 hand zone")
        assertTrue(
            !handZone!!.objectInstanceIdsList.contains(origInstanceId),
            "Old instanceId $origInstanceId should NOT be in hand zone after play, got: ${handZone.objectInstanceIdsList}",
        )

        // BF + Limbo zone checks
        val bfZone = acc.zones[ZoneIds.BATTLEFIELD]
        assertTrue(bfZone != null, "Should have battlefield zone")
        assertTrue(
            bfZone!!.objectInstanceIdsList.contains(newInstanceId),
            "Battlefield should contain new instanceId $newInstanceId, got: ${bfZone.objectInstanceIdsList}",
        )

        val limboZone = acc.zones[ZoneIds.LIMBO]
        assertTrue(limboZone != null, "Should have Limbo zone")
        assertTrue(
            limboZone!!.objectInstanceIdsList.contains(origInstanceId),
            "Limbo should contain old instanceId $origInstanceId, got: ${limboZone.objectInstanceIdsList}",
        )

        acc.assertConsistent("after play land")
    }
}
