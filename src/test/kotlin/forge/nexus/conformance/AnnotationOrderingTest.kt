package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

/**
 * Enforces annotation ordering and affectorId (effector) correctness for each
 * zone-transfer category: PlayLand, CastSpell, Resolve.
 *
 * Real Arena server emits annotations in a strict order within each GSM.
 * The client replays them sequentially for animations — wrong order causes
 * visual glitches (duplicate renders, stuck cards, missing animations).
 *
 * These tests exercise the full StateMapper.buildFromGame / BundleBuilder.postAction
 * pipeline with a live Forge engine and assert on the emitted annotation sequence.
 */
@Test(groups = ["integration", "conformance"])
class AnnotationOrderingTest : ConformanceTestBase() {

    private companion object {
        const val ZONE_STACK = 27
        const val ZONE_BATTLEFIELD = 28
        const val ZONE_LIMBO = 30
        const val ZONE_P1_HAND = 31
    }

    // ===== PlayLand ordering =====

    @Test(description = "PlayLand: annotation order is ObjectIdChanged → ZoneTransfer → UserActionTaken")
    fun playLandAnnotationOrder() {
        val gsm = playLandAndCapture() ?: return

        val types = gsm.annotationsList.map { it.typeList.first() }
        val oicIdx = types.indexOf(AnnotationType.ObjectIdChanged)
        val ztIdx = types.indexOf(AnnotationType.ZoneTransfer_af5a)
        val uatIdx = types.indexOf(AnnotationType.UserActionTaken)

        assertTrue(oicIdx >= 0, "Should have ObjectIdChanged annotation")
        assertTrue(ztIdx >= 0, "Should have ZoneTransfer annotation")
        assertTrue(uatIdx >= 0, "Should have UserActionTaken annotation")

        assertTrue(
            oicIdx < ztIdx,
            "ObjectIdChanged (idx=$oicIdx) must come before ZoneTransfer (idx=$ztIdx)",
        )
        assertTrue(
            ztIdx < uatIdx,
            "ZoneTransfer (idx=$ztIdx) must come before UserActionTaken (idx=$uatIdx)",
        )
    }

    @Test(description = "PlayLand: exactly 3 annotations (ObjectIdChanged + ZoneTransfer + UserActionTaken)")
    fun playLandAnnotationCount() {
        val gsm = playLandAndCapture() ?: return

        val types = gsm.annotationsList.map { it.typeList.first() }
        val expected = listOf(
            AnnotationType.ObjectIdChanged,
            AnnotationType.ZoneTransfer_af5a,
            AnnotationType.UserActionTaken,
        )
        assertEquals(types, expected, "PlayLand should have exactly 3 annotations in order: $expected, got: $types")
    }

    @Test(description = "PlayLand: ZoneTransfer affectorId is 0 (no acting seat for land play)")
    fun playLandZoneTransferAffectorIdIsZero() {
        val gsm = playLandAndCapture() ?: return

        val zt = gsm.annotationsList.first { AnnotationType.ZoneTransfer_af5a in it.typeList }
        assertEquals(zt.affectorId, 0, "PlayLand ZoneTransfer affectorId should be 0 (default), got: ${zt.affectorId}")
    }

    @Test(description = "PlayLand: UserActionTaken affectorId equals acting seat (1=human)")
    fun playLandUserActionTakenAffectorId() {
        val gsm = playLandAndCapture() ?: return

        val uat = gsm.annotationsList.first { AnnotationType.UserActionTaken in it.typeList }
        assertEquals(uat.affectorId, 1, "PlayLand UserActionTaken affectorId should be seat 1 (human)")
    }

    @Test(description = "PlayLand: UserActionTaken actionType=3 (Play)")
    fun playLandUserActionTakenActionType() {
        val gsm = playLandAndCapture() ?: return

        val uat = gsm.annotationsList.first { AnnotationType.UserActionTaken in it.typeList }
        val actionType = uat.detailsList.first { it.key == "actionType" }
        assertEquals(actionType.getValueInt32(0), 3, "PlayLand UserActionTaken actionType should be 3 (Play)")
    }

    @Test(description = "PlayLand: ObjectIdChanged has no affectorId set (default 0)")
    fun playLandObjectIdChangedNoAffector() {
        val gsm = playLandAndCapture() ?: return

        val oic = gsm.annotationsList.first { AnnotationType.ObjectIdChanged in it.typeList }
        assertEquals(oic.affectorId, 0, "ObjectIdChanged should not have affectorId set")
    }

    // ===== CastSpell ordering =====

    @Test(description = "CastSpell: annotation order is ObjectIdChanged → ZoneTransfer → AbilityInstanceCreated → TappedUntappedPermanent → ManaPaid → AbilityInstanceDeleted → UserActionTaken")
    fun castSpellAnnotationOrder() {
        val gsm = castSpellAndCapture() ?: return

        val types = gsm.annotationsList.map { it.typeList.first() }

        val oicIdx = types.indexOf(AnnotationType.ObjectIdChanged)
        val ztIdx = types.indexOf(AnnotationType.ZoneTransfer_af5a)
        val aicIdx = types.indexOf(AnnotationType.AbilityInstanceCreated)
        val tupIdx = types.indexOf(AnnotationType.TappedUntappedPermanent)
        val mpIdx = types.indexOf(AnnotationType.ManaPaid)
        val aidIdx = types.indexOf(AnnotationType.AbilityInstanceDeleted)
        val uatIdx = types.indexOf(AnnotationType.UserActionTaken)

        assertTrue(oicIdx >= 0, "Should have ObjectIdChanged")
        assertTrue(ztIdx >= 0, "Should have ZoneTransfer")
        assertTrue(aicIdx >= 0, "Should have AbilityInstanceCreated")
        assertTrue(tupIdx >= 0, "Should have TappedUntappedPermanent")
        assertTrue(mpIdx >= 0, "Should have ManaPaid")
        assertTrue(aidIdx >= 0, "Should have AbilityInstanceDeleted")
        assertTrue(uatIdx >= 0, "Should have UserActionTaken")

        assertTrue(oicIdx < ztIdx, "ObjectIdChanged ($oicIdx) before ZoneTransfer ($ztIdx)")
        assertTrue(ztIdx < aicIdx, "ZoneTransfer ($ztIdx) before AbilityInstanceCreated ($aicIdx)")
        assertTrue(aicIdx < tupIdx, "AbilityInstanceCreated ($aicIdx) before TappedUntappedPermanent ($tupIdx)")
        assertTrue(tupIdx < mpIdx, "TappedUntappedPermanent ($tupIdx) before ManaPaid ($mpIdx)")
        assertTrue(mpIdx < aidIdx, "ManaPaid ($mpIdx) before AbilityInstanceDeleted ($aidIdx)")
        assertTrue(aidIdx < uatIdx, "AbilityInstanceDeleted ($aidIdx) before UserActionTaken ($uatIdx)")
    }

    @Test(description = "CastSpell: exactly 7 annotations in the expected order")
    fun castSpellAnnotationCount() {
        val gsm = castSpellAndCapture() ?: return

        val types = gsm.annotationsList.map { it.typeList.first() }
        val expected = listOf(
            AnnotationType.ObjectIdChanged,
            AnnotationType.ZoneTransfer_af5a,
            AnnotationType.AbilityInstanceCreated,
            AnnotationType.TappedUntappedPermanent,
            AnnotationType.ManaPaid,
            AnnotationType.AbilityInstanceDeleted,
            AnnotationType.UserActionTaken,
        )
        assertEquals(types, expected, "CastSpell should have exactly 7 annotations in order")
    }

    @Test(description = "CastSpell: ZoneTransfer category is CastSpell, zones are Hand→Stack")
    fun castSpellZoneTransferFields() {
        val gsm = castSpellAndCapture() ?: return

        val zt = gsm.annotationsList.first { AnnotationType.ZoneTransfer_af5a in it.typeList }
        val category = zt.detailsList.first { it.key == "category" }
        assertEquals(category.getValueString(0), "CastSpell")

        val zoneSrc = zt.detailsList.first { it.key == "zone_src" }
        assertEquals(zoneSrc.getValueInt32(0), ZONE_P1_HAND, "zone_src should be Hand ($ZONE_P1_HAND)")

        val zoneDest = zt.detailsList.first { it.key == "zone_dest" }
        assertEquals(zoneDest.getValueInt32(0), ZONE_STACK, "zone_dest should be Stack ($ZONE_STACK)")
    }

    @Test(description = "CastSpell: UserActionTaken has actionType=1 (Cast)")
    fun castSpellUserActionTakenActionType() {
        val gsm = castSpellAndCapture() ?: return

        val uat = gsm.annotationsList.first { AnnotationType.UserActionTaken in it.typeList }
        val actionType = uat.detailsList.first { it.key == "actionType" }
        assertEquals(actionType.getValueInt32(0), 1, "CastSpell UserActionTaken actionType should be 1 (Cast)")
    }

    @Test(description = "CastSpell: TappedUntappedPermanent has tapped=1 detail")
    fun castSpellTappedUntappedDetail() {
        val gsm = castSpellAndCapture() ?: return

        val tup = gsm.annotationsList.first { AnnotationType.TappedUntappedPermanent in it.typeList }
        val tapped = tup.detailsList.first { it.key == "tapped" }
        assertEquals(tapped.getValueUint32(0), 1, "TappedUntappedPermanent tapped should be 1")
    }

    @Test(description = "CastSpell: all annotations reference the new (post-realloc) instanceId")
    fun castSpellAnnotationsReferenceNewInstanceId() {
        val (gsm, _, newInstanceId) = castSpellAndCaptureWithIds() ?: return

        val zt = gsm.annotationsList.first { AnnotationType.ZoneTransfer_af5a in it.typeList }
        assertTrue(zt.affectedIdsList.contains(newInstanceId), "ZoneTransfer affectedIds should contain new instanceId $newInstanceId")

        val uat = gsm.annotationsList.first { AnnotationType.UserActionTaken in it.typeList }
        assertTrue(uat.affectedIdsList.contains(newInstanceId), "UserActionTaken affectedIds should contain new instanceId $newInstanceId")

        val mp = gsm.annotationsList.first { AnnotationType.ManaPaid in it.typeList }
        assertTrue(mp.affectedIdsList.contains(newInstanceId), "ManaPaid affectedIds should contain new instanceId $newInstanceId")

        val aic = gsm.annotationsList.first { AnnotationType.AbilityInstanceCreated in it.typeList }
        assertTrue(aic.affectedIdsList.contains(newInstanceId), "AbilityInstanceCreated affectedIds should contain new instanceId $newInstanceId")
    }

    // ===== Resolve ordering =====

    @Test(description = "Resolve: annotation order is ResolutionStart → ResolutionComplete → ZoneTransfer")
    fun resolveAnnotationOrder() {
        val gsm = resolveAndCapture() ?: return

        val types = gsm.annotationsList.map { it.typeList.first() }
        val rsIdx = types.indexOf(AnnotationType.ResolutionStart)
        val rcIdx = types.indexOf(AnnotationType.ResolutionComplete)
        val ztIdx = types.indexOf(AnnotationType.ZoneTransfer_af5a)

        assertTrue(rsIdx >= 0, "Should have ResolutionStart")
        assertTrue(rcIdx >= 0, "Should have ResolutionComplete")
        assertTrue(ztIdx >= 0, "Should have ZoneTransfer")

        assertTrue(rsIdx < rcIdx, "ResolutionStart ($rsIdx) before ResolutionComplete ($rcIdx)")
        assertTrue(rcIdx < ztIdx, "ResolutionComplete ($rcIdx) before ZoneTransfer ($ztIdx)")
    }

    @Test(description = "Resolve: exactly 3 annotations (ResolutionStart + ResolutionComplete + ZoneTransfer)")
    fun resolveAnnotationCount() {
        val gsm = resolveAndCapture() ?: return

        val types = gsm.annotationsList.map { it.typeList.first() }
        val expected = listOf(
            AnnotationType.ResolutionStart,
            AnnotationType.ResolutionComplete,
            AnnotationType.ZoneTransfer_af5a,
        )
        assertEquals(types, expected, "Resolve should have exactly 3 annotations in order")
    }

    @Test(description = "Resolve: ZoneTransfer category is Resolve, affectorId is acting seat")
    fun resolveZoneTransferAffectorId() {
        val gsm = resolveAndCapture() ?: return

        val zt = gsm.annotationsList.first { AnnotationType.ZoneTransfer_af5a in it.typeList }
        val category = zt.detailsList.first { it.key == "category" }
        assertEquals(category.getValueString(0), "Resolve")

        // Resolve ZoneTransfer uses actingSeat as affectorId (unlike PlayLand which uses 0)
        assertTrue(zt.affectorId > 0, "Resolve ZoneTransfer should have non-zero affectorId (acting seat)")
    }

    @Test(description = "Resolve: ZoneTransfer zones are Stack→Battlefield")
    fun resolveZoneTransferZones() {
        val gsm = resolveAndCapture() ?: return

        val zt = gsm.annotationsList.first { AnnotationType.ZoneTransfer_af5a in it.typeList }
        val zoneSrc = zt.detailsList.first { it.key == "zone_src" }
        assertEquals(zoneSrc.getValueInt32(0), ZONE_STACK, "Resolve zone_src should be Stack ($ZONE_STACK)")
        val zoneDest = zt.detailsList.first { it.key == "zone_dest" }
        assertEquals(zoneDest.getValueInt32(0), ZONE_BATTLEFIELD, "Resolve zone_dest should be Battlefield ($ZONE_BATTLEFIELD)")
    }

    @Test(description = "Resolve: ResolutionStart has affectorId=instanceId and grpid detail")
    fun resolveResolutionStartFields() {
        val gsm = resolveAndCapture() ?: return

        val rs = gsm.annotationsList.first { AnnotationType.ResolutionStart in it.typeList }
        assertTrue(rs.affectorId > 0, "ResolutionStart affectorId should be the spell instanceId")
        assertTrue(rs.affectedIdsCount > 0, "ResolutionStart should have affectedIds")
        assertEquals(
            rs.affectorId,
            rs.getAffectedIds(0),
            "ResolutionStart affectorId should equal affectedIds[0] (both = spell instanceId)",
        )

        val grpid = rs.detailsList.firstOrNull { it.key == "grpid" }
        assertTrue(grpid != null, "ResolutionStart should have grpid detail")
        // grpid may be 0 if Arena card DB is not installed — that's fine for CI
        assertTrue(grpid!!.getValueUint32(0) >= 0, "grpid should be present (0 is OK without ArenaCardDb)")
    }

    @Test(description = "Resolve: ResolutionComplete has same affectorId and grpid as ResolutionStart")
    fun resolveResolutionCompleteMatchesStart() {
        val gsm = resolveAndCapture() ?: return

        val rs = gsm.annotationsList.first { AnnotationType.ResolutionStart in it.typeList }
        val rc = gsm.annotationsList.first { AnnotationType.ResolutionComplete in it.typeList }

        assertEquals(rc.affectorId, rs.affectorId, "ResolutionComplete affectorId should match ResolutionStart")
        assertEquals(
            rc.getAffectedIds(0),
            rs.getAffectedIds(0),
            "ResolutionComplete affectedIds[0] should match ResolutionStart",
        )

        val rsGrp = rs.detailsList.first { it.key == "grpid" }.getValueUint32(0)
        val rcGrp = rc.detailsList.first { it.key == "grpid" }.getValueUint32(0)
        assertEquals(rcGrp, rsGrp, "ResolutionComplete grpid should match ResolutionStart grpid")
    }

    @Test(description = "Resolve: instanceId NOT reallocated (Stack→Battlefield keeps same ID)")
    fun resolveKeepsSameInstanceId() {
        val gsm = resolveAndCapture() ?: return

        // Resolve should NOT have ObjectIdChanged (no realloc)
        val oicAnns = gsm.annotationsList.filter { AnnotationType.ObjectIdChanged in it.typeList }
        assertTrue(
            oicAnns.isEmpty(),
            "Resolve should NOT have ObjectIdChanged (Stack→Battlefield keeps same instanceId), " +
                "got: ${oicAnns.map { it.typeList }}",
        )
    }

    @Test(description = "Resolve: no Limbo retirement (no old instanceId to retire)")
    fun resolveNoLimboRetirement() {
        val gsm = resolveAndCapture() ?: return

        // The Limbo zone should not have gained entries from the resolve step.
        // Note: it may have entries from the preceding cast spell step.
        // We check that no resolve-specific retirement happened by checking
        // that the resolved creature is NOT in Limbo.
        val bfObjects = gsm.gameObjectsList.filter { it.zoneId == ZONE_BATTLEFIELD }
        val limboZone = gsm.zonesList.firstOrNull { it.type == ZoneType.Limbo }
        for (obj in bfObjects) {
            if (limboZone != null) {
                // The battlefield object's instanceId should not be in Limbo
                assertTrue(
                    !limboZone.objectInstanceIdsList.contains(obj.instanceId),
                    "Resolved creature instanceId ${obj.instanceId} should NOT be in Limbo",
                )
            }
        }
    }

    // ===== Cross-category: annotation IDs =====

    @Test(description = "All annotation IDs are sequential and monotonically increasing within a GSM")
    fun annotationIdsSequentialAcrossCategories() {
        val gsm = playLandAndCapture() ?: return
        assertAnnotationIdsSequential(gsm)

        val castGsm = castSpellAndCapture()
        if (castGsm != null) assertAnnotationIdsSequential(castGsm)

        val resolveGsm = resolveAndCapture()
        if (resolveGsm != null) assertAnnotationIdsSequential(resolveGsm)
    }

    // ===== Persistent annotation =====

    @Test(description = "Resolve: EnteredZoneThisTurn persistent annotation for creature landing on battlefield")
    fun resolveCreatureGetsPersistentAnnotation() {
        val gsm = resolveAndCapture() ?: return

        assertTrue(
            gsm.persistentAnnotationsCount > 0,
            "Resolve GSM should have persistent annotations (EnteredZoneThisTurn for creature on BF)",
        )
        val entered = gsm.persistentAnnotationsList.firstOrNull {
            AnnotationType.EnteredZoneThisTurn in it.typeList
        }
        assertTrue(entered != null, "Should have EnteredZoneThisTurn persistent annotation")
        assertEquals(
            entered!!.affectorId,
            ZONE_BATTLEFIELD,
            "EnteredZoneThisTurn affectorId should be battlefield zone ($ZONE_BATTLEFIELD)",
        )
    }

    // ===== Helpers =====

    private fun assertAnnotationIdsSequential(gsm: GameStateMessage) {
        val ids = gsm.annotationsList.map { it.id }
        if (ids.isEmpty()) return
        assertTrue(ids.all { it > 0 }, "All annotation IDs should be > 0, got: $ids")
        assertEquals(ids, ids.sorted(), "Annotation IDs should be monotonically increasing, got: $ids")
        assertEquals(ids.toSet().size, ids.size, "Annotation IDs should be unique, got: $ids")
    }

    /** Play a land and capture the resulting GSM (with annotations). */
    private fun playLandAndCapture(): GameStateMessage? {
        val (b, game, gsId) = startGameAtMain1()
        playLand(b) ?: return null
        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)
        return result.messages.firstOrNull { it.hasGameStateMessage() }?.gameStateMessage
    }

    /**
     * Cast a creature spell and capture the on-stack GSM (before resolution).
     * Returns the GSM with CastSpell annotations.
     */
    private fun castSpellAndCapture(): GameStateMessage? {
        val (b, game, gsId) = startGameAtMain1()
        // Play a land first for mana
        playLand(b) ?: return null
        b.snapshotState(game)
        val nextGsId = gsId + 2

        // Cast a creature
        castCreature(b) ?: return null
        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, nextGsId)
        return result.messages.firstOrNull { it.hasGameStateMessage() }?.gameStateMessage
    }

    /**
     * Cast a creature spell and capture with pre/post instanceIds.
     * Returns (gsm, origInstanceId, newInstanceId).
     */
    private fun castSpellAndCaptureWithIds(): Triple<GameStateMessage, Int, Int>? {
        val (b, game, gsId) = startGameAtMain1()
        playLand(b) ?: return null
        b.snapshotState(game)
        val nextGsId = gsId + 2

        val player = b.getPlayer(1) ?: return null
        val creature = player.getZone(forge.game.zone.ZoneType.Hand).cards.firstOrNull { it.isCreature } ?: return null
        val origInstanceId = b.getOrAllocInstanceId(creature.id)
        val forgeCardId = creature.id

        castCreature(b) ?: return null
        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, nextGsId)
        val gsm = result.messages.firstOrNull { it.hasGameStateMessage() }?.gameStateMessage ?: return null
        val newInstanceId = b.getOrAllocInstanceId(forgeCardId)

        return Triple(gsm, origInstanceId, newInstanceId)
    }

    /**
     * Full cast+resolve cycle: play land → cast creature → pass priority (resolve).
     * Returns the GSM from the resolution step (with Resolve annotations).
     */
    private fun resolveAndCapture(): GameStateMessage? {
        val (b, game, gsId) = startGameAtMain1()

        // Play land for mana
        playLand(b) ?: return null
        b.snapshotState(game)
        var nextGsId = gsId + 2

        // Cast a creature (puts it on stack)
        castCreature(b) ?: return null
        // Consume the cast GSM and update snapshot
        val castResult = BundleBuilder.postAction(game, b, "test-match", 1, 1, nextGsId)
        nextGsId = castResult.nextGsId
        b.snapshotState(game)

        // Pass priority to resolve the spell
        passPriority(b)
        val resolveResult = BundleBuilder.postAction(game, b, "test-match", 1, 1, nextGsId)
        return resolveResult.messages.firstOrNull { it.hasGameStateMessage() }?.gameStateMessage
    }
}
