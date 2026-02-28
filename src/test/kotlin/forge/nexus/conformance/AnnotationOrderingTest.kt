package forge.nexus.conformance

import forge.nexus.game.mapper.ZoneIds
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
 * Real client server emits annotations in a strict order within each GSM.
 * The client replays them sequentially for animations — wrong order causes
 * visual glitches (duplicate renders, stuck cards, missing animations).
 *
 * These tests exercise the full StateMapper.buildFromGame / BundleBuilder.postAction
 * pipeline with a live Forge engine and assert on the emitted annotation sequence.
 */
@Test(groups = ["conformance"])
class AnnotationOrderingTest : ConformanceTestBase() {

    // ===== PlayLand ordering =====

    @Test(description = "PlayLand: annotation order is ObjectIdChanged -> ZoneTransfer -> UserActionTaken")
    fun playLandAnnotationOrder() {
        val gsm = playLandAndCapture() ?: error("No land in hand at seed 42")

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
        val gsm = playLandAndCapture() ?: error("No land in hand at seed 42")

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
        val gsm = playLandAndCapture() ?: error("No land in hand at seed 42")

        val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
        assertEquals(zt.affectorId, 0, "PlayLand ZoneTransfer affectorId should be 0 (default), got: ${zt.affectorId}")
    }

    @Test(description = "PlayLand: UserActionTaken affectorId equals acting seat (1=human)")
    fun playLandUserActionTakenAffectorId() {
        val gsm = playLandAndCapture() ?: error("No land in hand at seed 42")

        val uat = gsm.annotation(AnnotationType.UserActionTaken)
        assertEquals(uat.affectorId, 1, "PlayLand UserActionTaken affectorId should be seat 1 (human)")
    }

    @Test(description = "PlayLand: UserActionTaken actionType=3 (Play)")
    fun playLandUserActionTakenActionType() {
        val gsm = playLandAndCapture() ?: error("No land in hand at seed 42")

        val uat = gsm.annotation(AnnotationType.UserActionTaken)
        assertEquals(uat.detailInt("actionType"), 3, "PlayLand UserActionTaken actionType should be 3 (Play)")
    }

    @Test(description = "PlayLand: ObjectIdChanged has no affectorId set (default 0)")
    fun playLandObjectIdChangedNoAffector() {
        val gsm = playLandAndCapture() ?: error("No land in hand at seed 42")

        val oic = gsm.annotation(AnnotationType.ObjectIdChanged)
        assertEquals(oic.affectorId, 0, "ObjectIdChanged should not have affectorId set")
    }

    // ===== CastSpell ordering =====

    @Test(description = "CastSpell: annotation order is ObjectIdChanged -> ZoneTransfer -> AbilityInstanceCreated -> ManaPaid -> AbilityInstanceDeleted -> UserActionTaken (tap annotations come via Stage 4)")
    fun castSpellAnnotationOrder() {
        val gsm = castSpellAndCapture() ?: error("Could not cast spell at seed 42")

        val types = gsm.annotationsList.map { it.typeList.first() }

        val oicIdx = types.indexOf(AnnotationType.ObjectIdChanged)
        val ztIdx = types.indexOf(AnnotationType.ZoneTransfer_af5a)
        val aicIdx = types.indexOf(AnnotationType.AbilityInstanceCreated)
        val mpIdx = types.indexOf(AnnotationType.ManaPaid)
        val aidIdx = types.indexOf(AnnotationType.AbilityInstanceDeleted)
        val uatIdx = types.indexOf(AnnotationType.UserActionTaken)

        assertTrue(oicIdx >= 0, "Should have ObjectIdChanged")
        assertTrue(ztIdx >= 0, "Should have ZoneTransfer")
        assertTrue(aicIdx >= 0, "Should have AbilityInstanceCreated")
        assertTrue(mpIdx >= 0, "Should have ManaPaid")
        assertTrue(aidIdx >= 0, "Should have AbilityInstanceDeleted")
        assertTrue(uatIdx >= 0, "Should have UserActionTaken")

        assertTrue(oicIdx < ztIdx, "ObjectIdChanged ($oicIdx) before ZoneTransfer ($ztIdx)")
        assertTrue(ztIdx < aicIdx, "ZoneTransfer ($ztIdx) before AbilityInstanceCreated ($aicIdx)")
        assertTrue(aicIdx < mpIdx, "AbilityInstanceCreated ($aicIdx) before ManaPaid ($mpIdx)")
        assertTrue(mpIdx < aidIdx, "ManaPaid ($mpIdx) before AbilityInstanceDeleted ($aidIdx)")
        assertTrue(aidIdx < uatIdx, "AbilityInstanceDeleted ($aidIdx) before UserActionTaken ($uatIdx)")

        // TappedUntappedPermanent annotations come via Stage 4 (per-land, after CastSpell sequence)
        val tupIndices = types.indices.filter { types[it] == AnnotationType.TappedUntappedPermanent }
        for (tupIdx in tupIndices) {
            assertTrue(tupIdx > uatIdx, "TappedUntappedPermanent ($tupIdx) should come after CastSpell sequence (UserActionTaken=$uatIdx)")
        }
    }

    @Test(description = "CastSpell: CastSpell sequence has 6 annotations + per-land TappedUntappedPermanent from Stage 4")
    fun castSpellAnnotationCount() {
        val gsm = castSpellAndCapture() ?: error("Could not cast spell at seed 42")

        val types = gsm.annotationsList.map { it.typeList.first() }
        // First 6 are the CastSpell sequence (Stage 2), followed by per-land tap annotations (Stage 4)
        val castSequence = listOf(
            AnnotationType.ObjectIdChanged,
            AnnotationType.ZoneTransfer_af5a,
            AnnotationType.AbilityInstanceCreated,
            AnnotationType.ManaPaid,
            AnnotationType.AbilityInstanceDeleted,
            AnnotationType.UserActionTaken,
        )
        assertEquals(types.take(6), castSequence, "First 6 annotations should be the CastSpell sequence")
        // Remaining annotations should be TappedUntappedPermanent (one per tapped land)
        val tapAnnotations = types.drop(6)
        assertTrue(tapAnnotations.isNotEmpty(), "Should have at least one TappedUntappedPermanent from Stage 4")
        assertTrue(
            tapAnnotations.all { it == AnnotationType.TappedUntappedPermanent },
            "All annotations after CastSpell sequence should be TappedUntappedPermanent, got: $tapAnnotations",
        )
    }

    @Test(description = "CastSpell: ZoneTransfer category is CastSpell, zones are Hand->Stack")
    fun castSpellZoneTransferFields() {
        val gsm = castSpellAndCapture() ?: error("Could not cast spell at seed 42")

        val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
        assertEquals(zt.detailString("category"), "CastSpell")
        assertEquals(zt.detailInt("zone_src"), ZoneIds.P1_HAND, "zone_src should be Hand (${ZoneIds.P1_HAND})")
        assertEquals(zt.detailInt("zone_dest"), ZoneIds.STACK, "zone_dest should be Stack (${ZoneIds.STACK})")
    }

    @Test(description = "CastSpell: UserActionTaken has actionType=1 (Cast)")
    fun castSpellUserActionTakenActionType() {
        val gsm = castSpellAndCapture() ?: error("Could not cast spell at seed 42")

        val uat = gsm.annotation(AnnotationType.UserActionTaken)
        assertEquals(uat.detailInt("actionType"), 1, "CastSpell UserActionTaken actionType should be 1 (Cast)")
    }

    @Test(description = "CastSpell: TappedUntappedPermanent annotations have tapped=1 detail (from Stage 4 per-land events)")
    fun castSpellTappedUntappedDetail() {
        val gsm = castSpellAndCapture() ?: error("Could not cast spell at seed 42")

        val tups = gsm.annotationsList.filter { AnnotationType.TappedUntappedPermanent in it.typeList }
        assertTrue(tups.isNotEmpty(), "Should have at least one TappedUntappedPermanent annotation")
        for (tup in tups) {
            assertEquals(tup.detailUint("tapped"), 1, "TappedUntappedPermanent tapped should be 1")
        }
    }

    @Test(description = "CastSpell: all annotations reference the new (post-realloc) instanceId")
    fun castSpellAnnotationsReferenceNewInstanceId() {
        val (gsm, _, newInstanceId) = castSpellAndCaptureWithIds() ?: error("Could not cast spell at seed 42")

        val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
        assertTrue(zt.affectedIdsList.contains(newInstanceId), "ZoneTransfer affectedIds should contain new instanceId $newInstanceId")

        val uat = gsm.annotation(AnnotationType.UserActionTaken)
        assertTrue(uat.affectedIdsList.contains(newInstanceId), "UserActionTaken affectedIds should contain new instanceId $newInstanceId")

        val mp = gsm.annotation(AnnotationType.ManaPaid)
        assertTrue(mp.affectedIdsList.contains(newInstanceId), "ManaPaid affectedIds should contain new instanceId $newInstanceId")

        val aic = gsm.annotation(AnnotationType.AbilityInstanceCreated)
        assertTrue(aic.affectedIdsList.contains(newInstanceId), "AbilityInstanceCreated affectedIds should contain new instanceId $newInstanceId")
    }

    // ===== Resolve ordering =====

    @Test(description = "Resolve: annotation order is ResolutionStart -> ResolutionComplete -> ZoneTransfer")
    fun resolveAnnotationOrder() {
        val gsm = resolveAndCapture() ?: error("Nothing to resolve at seed 42")

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
        val gsm = resolveAndCapture() ?: error("Nothing to resolve at seed 42")

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
        val gsm = resolveAndCapture() ?: error("Nothing to resolve at seed 42")

        val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
        assertEquals(zt.detailString("category"), "Resolve")
        assertTrue(zt.affectorId > 0, "Resolve ZoneTransfer should have non-zero affectorId (acting seat)")
    }

    @Test(description = "Resolve: ZoneTransfer zones are Stack->Battlefield")
    fun resolveZoneTransferZones() {
        val gsm = resolveAndCapture() ?: error("Nothing to resolve at seed 42")

        val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
        assertEquals(zt.detailInt("zone_src"), ZoneIds.STACK, "Resolve zone_src should be Stack (${ZoneIds.STACK})")
        assertEquals(zt.detailInt("zone_dest"), ZoneIds.BATTLEFIELD, "Resolve zone_dest should be Battlefield (${ZoneIds.BATTLEFIELD})")
    }

    @Test(description = "Resolve: ResolutionStart has affectorId=instanceId and grpid detail")
    fun resolveResolutionStartFields() {
        val gsm = resolveAndCapture() ?: error("Nothing to resolve at seed 42")

        val rs = gsm.annotation(AnnotationType.ResolutionStart)
        assertTrue(rs.affectorId > 0, "ResolutionStart affectorId should be the spell instanceId")
        assertTrue(rs.affectedIdsCount > 0, "ResolutionStart should have affectedIds")
        assertEquals(
            rs.affectorId,
            rs.getAffectedIds(0),
            "ResolutionStart affectorId should equal affectedIds[0] (both = spell instanceId)",
        )

        val grpid = rs.detail("grpid")
        assertTrue(grpid != null, "ResolutionStart should have grpid detail")
        assertTrue(grpid!!.getValueUint32(0) >= 0, "grpid should be present (0 is OK without client card DB)")
    }

    @Test(description = "Resolve: ResolutionComplete has same affectorId and grpid as ResolutionStart")
    fun resolveResolutionCompleteMatchesStart() {
        val gsm = resolveAndCapture() ?: error("Nothing to resolve at seed 42")

        val rs = gsm.annotation(AnnotationType.ResolutionStart)
        val rc = gsm.annotation(AnnotationType.ResolutionComplete)

        assertEquals(rc.affectorId, rs.affectorId, "ResolutionComplete affectorId should match ResolutionStart")
        assertEquals(
            rc.getAffectedIds(0),
            rs.getAffectedIds(0),
            "ResolutionComplete affectedIds[0] should match ResolutionStart",
        )

        assertEquals(
            rc.detailUint("grpid"),
            rs.detailUint("grpid"),
            "ResolutionComplete grpid should match ResolutionStart grpid",
        )
    }

    @Test(description = "Resolve: instanceId NOT reallocated (Stack->Battlefield keeps same ID)")
    fun resolveKeepsSameInstanceId() {
        val gsm = resolveAndCapture() ?: error("Nothing to resolve at seed 42")

        val oicAnns = gsm.annotationsList.filter { AnnotationType.ObjectIdChanged in it.typeList }
        assertTrue(
            oicAnns.isEmpty(),
            "Resolve should NOT have ObjectIdChanged (Stack->Battlefield keeps same instanceId), " +
                "got: ${oicAnns.map { it.typeList }}",
        )
    }

    @Test(description = "Resolve: no Limbo retirement (no old instanceId to retire)")
    fun resolveNoLimboRetirement() {
        val gsm = resolveAndCapture() ?: error("Nothing to resolve at seed 42")

        val bfObjects = gsm.gameObjectsList.filter { it.zoneId == ZoneIds.BATTLEFIELD }
        val limboZone = gsm.zonesList.firstOrNull { it.type == ZoneType.Limbo }
        for (obj in bfObjects) {
            if (limboZone != null) {
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
        val gsm = playLandAndCapture() ?: error("No land in hand at seed 42")
        assertAnnotationIdsSequential(gsm)

        val castGsm = castSpellAndCapture()
        if (castGsm != null) assertAnnotationIdsSequential(castGsm)

        val resolveGsm = resolveAndCapture()
        if (resolveGsm != null) assertAnnotationIdsSequential(resolveGsm)
    }

    // ===== Persistent annotation =====

    @Test(description = "Resolve: EnteredZoneThisTurn persistent annotation for creature landing on battlefield")
    fun resolveCreatureGetsPersistentAnnotation() {
        val gsm = resolveAndCapture() ?: error("Nothing to resolve at seed 42")

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
            ZoneIds.BATTLEFIELD,
            "EnteredZoneThisTurn affectorId should be battlefield zone (${ZoneIds.BATTLEFIELD})",
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
}
