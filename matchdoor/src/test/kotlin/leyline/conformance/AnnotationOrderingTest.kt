package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.game.mapper.ZoneIds
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
class AnnotationOrderingTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        fun assertAnnotationIdsSequential(gsm: GameStateMessage) {
            val ids = gsm.annotationsList.map { it.id }
            ids.shouldNotBeEmpty()
            ids shouldBe ids.sorted()
            ids.toSet().size shouldBe ids.size
        }

        // ===== PlayLand ordering =====

        test("PlayLand annotation order: ObjectIdChanged -> ZoneTransfer -> UserActionTaken") {
            val gsm = base.playLandAndCapture() ?: error("No land in hand at seed 42")

            val types = gsm.annotationsList.map { it.typeList.first() }
            val oicIdx = types.indexOf(AnnotationType.ObjectIdChanged)
            val ztIdx = types.indexOf(AnnotationType.ZoneTransfer_af5a)
            val uatIdx = types.indexOf(AnnotationType.UserActionTaken)

            oicIdx shouldBeGreaterThanOrEqual 0
            ztIdx shouldBeGreaterThanOrEqual 0
            uatIdx shouldBeGreaterThanOrEqual 0

            (oicIdx < ztIdx).shouldBeTrue()
            (ztIdx < uatIdx).shouldBeTrue()
        }

        test("PlayLand: exactly 3 annotations") {
            val gsm = base.playLandAndCapture() ?: error("No land in hand at seed 42")

            val types = gsm.annotationsList.map { it.typeList.first() }
            val expected = listOf(
                AnnotationType.ObjectIdChanged,
                AnnotationType.ZoneTransfer_af5a,
                AnnotationType.UserActionTaken,
            )
            types shouldBe expected
        }

        test("PlayLand: ZoneTransfer affectorId is zero") {
            val gsm = base.playLandAndCapture() ?: error("No land in hand at seed 42")

            val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
            zt.affectorId shouldBe 0
        }

        test("PlayLand: UserActionTaken affectorId equals acting seat") {
            val gsm = base.playLandAndCapture() ?: error("No land in hand at seed 42")

            val uat = gsm.annotation(AnnotationType.UserActionTaken)
            uat.affectorId shouldBe 1
        }

        test("PlayLand: UserActionTaken actionType=3 (Play)") {
            val gsm = base.playLandAndCapture() ?: error("No land in hand at seed 42")

            val uat = gsm.annotation(AnnotationType.UserActionTaken)
            uat.detailInt("actionType") shouldBe 3
        }

        test("PlayLand: ObjectIdChanged has no affectorId set") {
            val gsm = base.playLandAndCapture() ?: error("No land in hand at seed 42")

            val oic = gsm.annotation(AnnotationType.ObjectIdChanged)
            oic.affectorId shouldBe 0
        }

        // ===== CastSpell ordering =====

        test("CastSpell annotation order") {
            val gsm = base.castSpellAndCapture() ?: error("Could not cast spell at seed 42")

            val types = gsm.annotationsList.map { it.typeList.first() }

            val oicIdx = types.indexOf(AnnotationType.ObjectIdChanged)
            val ztIdx = types.indexOf(AnnotationType.ZoneTransfer_af5a)
            val aicIdx = types.indexOf(AnnotationType.AbilityInstanceCreated)
            val mpIdx = types.indexOf(AnnotationType.ManaPaid)
            val aidIdx = types.indexOf(AnnotationType.AbilityInstanceDeleted)
            val uatIdx = types.indexOf(AnnotationType.UserActionTaken)

            oicIdx shouldBeGreaterThanOrEqual 0
            ztIdx shouldBeGreaterThanOrEqual 0
            aicIdx shouldBeGreaterThanOrEqual 0
            mpIdx shouldBeGreaterThanOrEqual 0
            aidIdx shouldBeGreaterThanOrEqual 0
            uatIdx shouldBeGreaterThanOrEqual 0

            (oicIdx < ztIdx).shouldBeTrue()
            (ztIdx < aicIdx).shouldBeTrue()
            (aicIdx < mpIdx).shouldBeTrue()
            (mpIdx < aidIdx).shouldBeTrue()
            (aidIdx < uatIdx).shouldBeTrue()

            // TappedUntappedPermanent annotations come via Stage 4
            val tupIndices = types.indices.filter { types[it] == AnnotationType.TappedUntappedPermanent }
            for (tupIdx in tupIndices) {
                (tupIdx > uatIdx).shouldBeTrue()
            }
        }

        test("CastSpell: 6 annotations + per-land TappedUntappedPermanent") {
            val gsm = base.castSpellAndCapture() ?: error("Could not cast spell at seed 42")

            val types = gsm.annotationsList.map { it.typeList.first() }
            val castSequence = listOf(
                AnnotationType.ObjectIdChanged,
                AnnotationType.ZoneTransfer_af5a,
                AnnotationType.AbilityInstanceCreated,
                AnnotationType.ManaPaid,
                AnnotationType.AbilityInstanceDeleted,
                AnnotationType.UserActionTaken,
            )
            types.take(6) shouldBe castSequence
            val tapAnnotations = types.drop(6)
            tapAnnotations.isNotEmpty().shouldBeTrue()
            tapAnnotations.all { it == AnnotationType.TappedUntappedPermanent }.shouldBeTrue()
        }

        test("CastSpell: ZoneTransfer category is CastSpell, zones are Hand->Stack") {
            val gsm = base.castSpellAndCapture() ?: error("Could not cast spell at seed 42")

            val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
            zt.detailString("category") shouldBe "CastSpell"
            zt.detailInt("zone_src") shouldBe ZoneIds.P1_HAND
            zt.detailInt("zone_dest") shouldBe ZoneIds.STACK
        }

        test("CastSpell: UserActionTaken has actionType=1 (Cast)") {
            val gsm = base.castSpellAndCapture() ?: error("Could not cast spell at seed 42")

            val uat = gsm.annotation(AnnotationType.UserActionTaken)
            uat.detailInt("actionType") shouldBe 1
        }

        test("CastSpell: TappedUntappedPermanent has tapped=1 detail") {
            val gsm = base.castSpellAndCapture() ?: error("Could not cast spell at seed 42")

            val tups = gsm.annotationsList.filter { AnnotationType.TappedUntappedPermanent in it.typeList }
            tups.isNotEmpty().shouldBeTrue()
            for (tup in tups) {
                tup.detailUint("tapped") shouldBe 1
            }
        }

        test("CastSpell: all annotations reference the new instanceId") {
            val (gsm, _, newInstanceId) = base.castSpellAndCaptureWithIds() ?: error("Could not cast spell at seed 42")

            val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
            zt.affectedIdsList.contains(newInstanceId).shouldBeTrue()

            val uat = gsm.annotation(AnnotationType.UserActionTaken)
            uat.affectedIdsList.contains(newInstanceId).shouldBeTrue()

            val mp = gsm.annotation(AnnotationType.ManaPaid)
            mp.affectedIdsList.contains(newInstanceId).shouldBeTrue()

            val aic = gsm.annotation(AnnotationType.AbilityInstanceCreated)
            aic.affectedIdsList.contains(newInstanceId).shouldBeTrue()
        }

        // ===== Resolve ordering =====

        test("Resolve annotation order: ResolutionStart -> ResolutionComplete -> ZoneTransfer") {
            val gsm = base.resolveAndCapture() ?: error("Nothing to resolve at seed 42")

            val types = gsm.annotationsList.map { it.typeList.first() }
            val rsIdx = types.indexOf(AnnotationType.ResolutionStart)
            val rcIdx = types.indexOf(AnnotationType.ResolutionComplete)
            val ztIdx = types.indexOf(AnnotationType.ZoneTransfer_af5a)

            rsIdx shouldBeGreaterThanOrEqual 0
            rcIdx shouldBeGreaterThanOrEqual 0
            ztIdx shouldBeGreaterThanOrEqual 0

            (rsIdx < rcIdx).shouldBeTrue()
            (rcIdx < ztIdx).shouldBeTrue()
        }

        test("Resolve: exactly 3 annotations") {
            val gsm = base.resolveAndCapture() ?: error("Nothing to resolve at seed 42")

            val types = gsm.annotationsList.map { it.typeList.first() }
            val expected = listOf(
                AnnotationType.ResolutionStart,
                AnnotationType.ResolutionComplete,
                AnnotationType.ZoneTransfer_af5a,
            )
            types shouldBe expected
        }

        test("Resolve: ZoneTransfer category is Resolve, affectorId is acting seat") {
            val gsm = base.resolveAndCapture() ?: error("Nothing to resolve at seed 42")

            val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
            zt.detailString("category") shouldBe "Resolve"
            zt.affectorId shouldBeGreaterThan 0
        }

        test("Resolve: ZoneTransfer zones are Stack->Battlefield") {
            val gsm = base.resolveAndCapture() ?: error("Nothing to resolve at seed 42")

            val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
            zt.detailInt("zone_src") shouldBe ZoneIds.STACK
            zt.detailInt("zone_dest") shouldBe ZoneIds.BATTLEFIELD
        }

        test("Resolve: ResolutionStart fields") {
            val gsm = base.resolveAndCapture() ?: error("Nothing to resolve at seed 42")

            val rs = gsm.annotation(AnnotationType.ResolutionStart)
            rs.affectorId shouldBeGreaterThan 0
            (rs.affectedIdsCount > 0).shouldBeTrue()
            rs.affectorId shouldBe rs.getAffectedIds(0)

            val grpid = rs.detail("grpid")
            grpid shouldNotBe null
            (grpid!!.getValueUint32(0) >= 0).shouldBeTrue()
        }

        test("Resolve: ResolutionComplete matches ResolutionStart") {
            val gsm = base.resolveAndCapture() ?: error("Nothing to resolve at seed 42")

            val rs = gsm.annotation(AnnotationType.ResolutionStart)
            val rc = gsm.annotation(AnnotationType.ResolutionComplete)

            rc.affectorId shouldBe rs.affectorId
            rc.getAffectedIds(0) shouldBe rs.getAffectedIds(0)
            rc.detailUint("grpid") shouldBe rs.detailUint("grpid")
        }

        test("Resolve: instanceId NOT reallocated") {
            val gsm = base.resolveAndCapture() ?: error("Nothing to resolve at seed 42")

            val oicAnns = gsm.annotationsList.filter { AnnotationType.ObjectIdChanged in it.typeList }
            oicAnns.shouldBeEmpty()
        }

        test("Resolve: no Limbo retirement") {
            val gsm = base.resolveAndCapture() ?: error("Nothing to resolve at seed 42")

            val bfObjects = gsm.gameObjectsList.filter { it.zoneId == ZoneIds.BATTLEFIELD }
            val limboZone = gsm.zonesList.firstOrNull { it.type == ZoneType.Limbo }
            for (obj in bfObjects) {
                if (limboZone != null) {
                    limboZone.objectInstanceIdsList.contains(obj.instanceId) shouldBe false
                }
            }
        }

        // ===== Cross-category: annotation IDs =====

        test("annotation IDs sequential across categories") {
            val gsm = base.playLandAndCapture() ?: error("No land in hand at seed 42")
            assertAnnotationIdsSequential(gsm)

            val castGsm = base.castSpellAndCapture()
            if (castGsm != null) assertAnnotationIdsSequential(castGsm)

            val resolveGsm = base.resolveAndCapture()
            if (resolveGsm != null) assertAnnotationIdsSequential(resolveGsm)
        }

        // ===== Persistent annotation =====

        test("Resolve: EnteredZoneThisTurn persistent annotation") {
            val gsm = base.resolveAndCapture() ?: error("Nothing to resolve at seed 42")

            (gsm.persistentAnnotationsCount > 0).shouldBeTrue()
            val entered = gsm.persistentAnnotationsList.firstOrNull {
                AnnotationType.EnteredZoneThisTurn in it.typeList
            }
            entered shouldNotBe null
            entered!!.affectorId shouldBe ZoneIds.BATTLEFIELD
        }
    })
