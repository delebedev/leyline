package leyline.game.annotations

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.toWireId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationOrderEnforcer
import leyline.game.eid
import leyline.game.grp
import leyline.game.iid
import leyline.game.mapping.ZoneIds
import leyline.game.sid
import leyline.game.wid
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class AnnotationOrderEnforcerTest :
    FunSpec({

        tags(UnitTag)

        test("no-op when already ordered: ObjectIdChanged before ZoneTransfer") {
            val oic = AnnotationBuilder.objectIdChanged(origId = 100.iid, newId = 200.iid)
            val zt =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 200.iid,
                    srcZoneId = 31,
                    destZoneId = 28,
                    category = "PlayLand",
                )
            val uat = AnnotationBuilder.userActionTaken(instanceId = 200.iid, seatId = 1.sid, actionType = ActionType.Play_add3)

            val result = AnnotationOrderEnforcer.enforce(listOf(oic, zt, uat))

            result.map { it.typeList.first() } shouldBe
                listOf(
                    AnnotationType.ObjectIdChanged,
                    AnnotationType.ZoneTransfer_af5a,
                    AnnotationType.UserActionTaken,
                )
        }

        test("reorders when ZoneTransfer precedes ObjectIdChanged") {
            val oic = AnnotationBuilder.objectIdChanged(origId = 100.iid, newId = 200.iid)
            val zt =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 200.iid,
                    srcZoneId = 31,
                    destZoneId = 28,
                    category = "CastSpell",
                )
            // Deliberately wrong order: ZT before OIC
            val result = AnnotationOrderEnforcer.enforce(listOf(zt, oic))

            result.map { it.typeList.first() } shouldBe
                listOf(
                    AnnotationType.ObjectIdChanged,
                    AnnotationType.ZoneTransfer_af5a,
                )
        }

        test("no-op when no ObjectIdChanged present") {
            val zt =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 200.iid,
                    srcZoneId = 27,
                    destZoneId = 28,
                    category = "Resolve",
                )
            val rs = AnnotationBuilder.resolutionStart(instanceId = 200.iid, grpId = 12345.grp)
            val rc = AnnotationBuilder.resolutionComplete(instanceId = 200.iid, grpId = 12345.grp)

            val input = listOf(rs, rc, zt)
            val result = AnnotationOrderEnforcer.enforce(input)

            result.map { it.typeList.first() } shouldBe
                listOf(
                    AnnotationType.ResolutionStart,
                    AnnotationType.ResolutionComplete,
                    AnnotationType.ZoneTransfer_af5a,
                )
        }

        test("handles multiple ObjectIdChanged for different cards") {
            val oic1 = AnnotationBuilder.objectIdChanged(origId = 100.iid, newId = 200.iid)
            val oic2 = AnnotationBuilder.objectIdChanged(origId = 300.iid, newId = 400.iid)
            val zt1 =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 200.iid,
                    srcZoneId = 31,
                    destZoneId = 28,
                    category = "PlayLand",
                )
            val zt2 =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 400.iid,
                    srcZoneId = 31,
                    destZoneId = 27,
                    category = "CastSpell",
                )

            // Both correctly ordered
            val result = AnnotationOrderEnforcer.enforce(listOf(oic1, zt1, oic2, zt2))
            result.map { it.typeList.first() } shouldBe
                listOf(
                    AnnotationType.ObjectIdChanged,
                    AnnotationType.ZoneTransfer_af5a,
                    AnnotationType.ObjectIdChanged,
                    AnnotationType.ZoneTransfer_af5a,
                )
        }

        test("fixes interleaved wrong order with multiple cards") {
            val oic1 = AnnotationBuilder.objectIdChanged(origId = 100.iid, newId = 200.iid)
            val oic2 = AnnotationBuilder.objectIdChanged(origId = 300.iid, newId = 400.iid)
            val zt1 =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 200.iid,
                    srcZoneId = 31,
                    destZoneId = 28,
                    category = "PlayLand",
                )
            val zt2 =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 400.iid,
                    srcZoneId = 31,
                    destZoneId = 27,
                    category = "CastSpell",
                )

            // Wrong: both ZTs before their OICs
            val result = AnnotationOrderEnforcer.enforce(listOf(zt1, zt2, oic1, oic2))

            // OIC1 should precede ZT1, OIC2 should precede ZT2
            val oic1Idx =
                result.indexOfFirst {
                    it.typeList.first() == AnnotationType.ObjectIdChanged &&
                        it.affectedIdsList.contains(100)
                }
            val zt1Idx =
                result.indexOfFirst {
                    it.typeList.first() == AnnotationType.ZoneTransfer_af5a &&
                        it.affectedIdsList.contains(200)
                }
            val oic2Idx =
                result.indexOfFirst {
                    it.typeList.first() == AnnotationType.ObjectIdChanged &&
                        it.affectedIdsList.contains(300)
                }
            val zt2Idx =
                result.indexOfFirst {
                    it.typeList.first() == AnnotationType.ZoneTransfer_af5a &&
                        it.affectedIdsList.contains(400)
                }

            oic1Idx shouldBeLessThan zt1Idx
            oic2Idx shouldBeLessThan zt2Idx
        }

        test("affectorId reference also triggers reorder") {
            val oic = AnnotationBuilder.objectIdChanged(origId = 100.iid, newId = 200.iid)
            val ann =
                AnnotationBuilder.userActionTaken(
                    instanceId = 200.iid,
                    seatId = 1.sid,
                    actionType = ActionType.Cast,
                )

            // Wrong order: UAT (which has affectedIds containing 200) before OIC
            val result = AnnotationOrderEnforcer.enforce(listOf(ann, oic))

            result.map { it.typeList.first() } shouldBe
                listOf(
                    AnnotationType.ObjectIdChanged,
                    AnnotationType.UserActionTaken,
                )
        }

        // ===== Rule 2: Same-card incremental chaining =====

        test("Rule 2: DamageDealt before LayeredEffectCreated on same card") {
            val cardId = 500.iid
            val damage = AnnotationBuilder.damageDealt(sourceInstanceId = 100.iid, targetId = cardId.toWireId(), amount = 3)
            val effect = AnnotationBuilder.layeredEffectCreated(effectId = 7001.eid, affectorId = cardId)

            // Correct order
            val result = AnnotationOrderEnforcer.enforce(listOf(damage, effect))
            result.map { it.typeList.first() } shouldBe
                listOf(
                    AnnotationType.DamageDealt_af5a,
                    AnnotationType.LayeredEffectCreated,
                )
        }

        test("Rule 2: reorders LayeredEffectCreated before DamageDealt on same card") {
            val cardId = 500.iid
            val damage = AnnotationBuilder.damageDealt(sourceInstanceId = 100.iid, targetId = cardId.toWireId(), amount = 3)
            val effect = AnnotationBuilder.layeredEffectCreated(effectId = 7001.eid, affectorId = cardId)

            // Wrong order: effect before damage
            val result = AnnotationOrderEnforcer.enforce(listOf(effect, damage))
            result.map { it.typeList.first() } shouldBe
                listOf(
                    AnnotationType.DamageDealt_af5a,
                    AnnotationType.LayeredEffectCreated,
                )
        }

        test("Rule 2: CounterAdded before LayeredEffectCreated on same card") {
            val cardId = 500.iid
            val counter = AnnotationBuilder.counterAdded(instanceId = cardId, counterType = "+1/+1", amount = 1)
            val effect = AnnotationBuilder.layeredEffectCreated(effectId = 7002.eid, affectorId = cardId)

            // Wrong order
            val result = AnnotationOrderEnforcer.enforce(listOf(effect, counter))
            result.map { it.typeList.first() } shouldBe
                listOf(
                    AnnotationType.CounterAdded,
                    AnnotationType.LayeredEffectCreated,
                )
        }

        test("Rule 2: no-op when annotations affect different cards") {
            val damage = AnnotationBuilder.damageDealt(sourceInstanceId = 100.iid, targetId = 500.wid, amount = 3)
            val effect = AnnotationBuilder.layeredEffectCreated(effectId = 7001.eid, affectorId = 600.iid)

            // Different cards — no constraint, original order preserved
            val input = listOf(effect, damage)
            val result = AnnotationOrderEnforcer.enforce(input)
            result shouldBe input
        }

        test("Rule 2: ControllerChanged before TappedUntapped on same card") {
            val cardId = 500.iid
            val tap = AnnotationBuilder.tappedUntappedPermanent(permanentId = cardId, abilityId = 501.iid, tapped = true)
            val steal = AnnotationBuilder.controllerChanged(affectorId = 502.iid, instanceId = cardId)

            // Wrong order: tap before steal
            val result = AnnotationOrderEnforcer.enforce(listOf(tap, steal))
            result.map { it.typeList.first() } shouldBe
                listOf(
                    AnnotationType.ControllerChanged,
                    AnnotationType.TappedUntappedPermanent,
                )
        }

        // ===== Rules 1 + 2 combined =====

        test("Rules 1+2: ObjectIdChanged + DamageDealt + LayeredEffectCreated") {
            val oic = AnnotationBuilder.objectIdChanged(origId = 100.iid, newId = 500.iid)
            val damage = AnnotationBuilder.damageDealt(sourceInstanceId = 200.iid, targetId = 500.wid, amount = 2)
            val effect = AnnotationBuilder.layeredEffectCreated(effectId = 7001.eid, affectorId = 500.iid)

            // Correct order
            val result = AnnotationOrderEnforcer.enforce(listOf(oic, damage, effect))
            result.map { it.typeList.first() } shouldBe
                listOf(
                    AnnotationType.ObjectIdChanged,
                    AnnotationType.DamageDealt_af5a,
                    AnnotationType.LayeredEffectCreated,
                )
        }

        // ===== Stability =====

        test("unrelated annotations are not disturbed") {
            val oic = AnnotationBuilder.objectIdChanged(origId = 100.iid, newId = 200.iid)
            val zt =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 200.iid,
                    srcZoneId = 31,
                    destZoneId = 28,
                    category = "PlayLand",
                )
            // Unrelated annotation referencing a completely different ID
            val unrelated = AnnotationBuilder.tappedUntappedPermanent(permanentId = 500.iid, abilityId = 501.iid, tapped = true)

            val result = AnnotationOrderEnforcer.enforce(listOf(oic, unrelated, zt))

            // Unrelated stays between OIC and ZT (stable order preserved)
            result.map { it.typeList.first() } shouldBe
                listOf(
                    AnnotationType.ObjectIdChanged,
                    AnnotationType.TappedUntappedPermanent,
                    AnnotationType.ZoneTransfer_af5a,
                )
        }

        // ===== Rule 4: PhaseOrStepFirst =====

        test("Rule 4: reorders so PhaseOrStepModified leads") {
            val aic = AnnotationBuilder.abilityInstanceCreated(abilityInstanceId = 900.iid, sourceZoneId = 31)
            val counter = AnnotationBuilder.counterAdded(instanceId = 500.iid, counterType = "+1/+1", amount = 1)
            val posm = AnnotationBuilder.phaseOrStepModified(activeSeat = 1.sid, phase = 3, step = 0)

            val result = AnnotationOrderEnforcer.enforce(listOf(aic, counter, posm))

            result.map { it.typeList.first() } shouldBe
                listOf(
                    AnnotationType.PhaseOrStepModified,
                    AnnotationType.AbilityInstanceCreated,
                    AnnotationType.CounterAdded,
                )
        }

        test("Rule 4: no-op when PhaseOrStepModified already first") {
            val posm = AnnotationBuilder.phaseOrStepModified(activeSeat = 1.sid, phase = 3, step = 0)
            val aic = AnnotationBuilder.abilityInstanceCreated(abilityInstanceId = 900.iid, sourceZoneId = 31)

            val input = listOf(posm, aic)
            val result = AnnotationOrderEnforcer.enforce(input)

            result shouldBe input
        }

        test("Rule 4: no-op when PhaseOrStepModified absent") {
            val aic = AnnotationBuilder.abilityInstanceCreated(abilityInstanceId = 900.iid, sourceZoneId = 31)
            val counter = AnnotationBuilder.counterAdded(instanceId = 500.iid, counterType = "+1/+1", amount = 1)

            val input = listOf(aic, counter)
            val result = AnnotationOrderEnforcer.enforce(input)

            result shouldBe input
        }

        test("Rule 4: multiple PhaseOrStepModified preserve relative order") {
            val aic = AnnotationBuilder.abilityInstanceCreated(abilityInstanceId = 900.iid, sourceZoneId = 31)
            val posmA = AnnotationBuilder.phaseOrStepModified(activeSeat = 1.sid, phase = 3, step = 0)
            val posmB = AnnotationBuilder.phaseOrStepModified(activeSeat = 1.sid, phase = 4, step = 0)

            // Wrong order: AIC before both PoSMs; PoSMs in (a, b) order
            val result = AnnotationOrderEnforcer.enforce(listOf(aic, posmA, posmB))

            // Both PoSMs lead, preserving (a, b) order; AIC trails.
            result.map { it.typeList.first() to it.detailsList.firstOrNull { d -> d.key == "phase" }?.getValueInt32(0) } shouldBe
                listOf(
                    AnnotationType.PhaseOrStepModified to 3,
                    AnnotationType.PhaseOrStepModified to 4,
                    AnnotationType.AbilityInstanceCreated to null,
                )
        }

        // ===== Rule 5: ResolveTransferOrdering =====

        test("Rule 5: moves Resolve ZoneTransfer after ResolutionComplete") {
            val rs = AnnotationBuilder.resolutionStart(instanceId = 200.iid, grpId = 12345.grp)
            val rc = AnnotationBuilder.resolutionComplete(instanceId = 200.iid, grpId = 12345.grp)
            val zt =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 200.iid,
                    srcZoneId = 27,
                    destZoneId = 28,
                    category = "Resolve",
                )

            val result = AnnotationOrderEnforcer.enforce(listOf(rs, zt, rc))

            result.map { it.typeList.first() } shouldBe
                listOf(
                    AnnotationType.ResolutionStart,
                    AnnotationType.ResolutionComplete,
                    AnnotationType.ZoneTransfer_af5a,
                )
        }

        test("Rule 5: moves Resolve ZoneTransfer after the RS/RC pair") {
            val oic = AnnotationBuilder.objectIdChanged(origId = 100.iid, newId = 200.iid)
            val zt =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 200.iid,
                    srcZoneId = 27,
                    destZoneId = 28,
                    category = "Resolve",
                )
            val rs = AnnotationBuilder.resolutionStart(instanceId = 200.iid, grpId = 12345.grp)
            val rc = AnnotationBuilder.resolutionComplete(instanceId = 200.iid, grpId = 12345.grp)

            val result = AnnotationOrderEnforcer.enforce(listOf(oic, zt, rs, rc))

            result.map { it.typeList.first() } shouldBe
                listOf(
                    AnnotationType.ObjectIdChanged,
                    AnnotationType.ResolutionStart,
                    AnnotationType.ResolutionComplete,
                    AnnotationType.ZoneTransfer_af5a,
                )
        }

        test("Rule 5: ignores non-Resolve ZoneTransfer") {
            val rs = AnnotationBuilder.resolutionStart(instanceId = 200.iid, grpId = 12345.grp)
            val rc = AnnotationBuilder.resolutionComplete(instanceId = 200.iid, grpId = 12345.grp)
            val zt =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 200.iid,
                    srcZoneId = 31,
                    destZoneId = 28,
                    category = "PlayLand",
                )

            val input = listOf(rs, rc, zt)
            val result = AnnotationOrderEnforcer.enforce(input)

            result shouldBe input
        }

        test("Rule 5: leaves non-stack Resolve ZoneTransfer inside ResolutionStart and ResolutionComplete") {
            val rs = AnnotationBuilder.resolutionStart(instanceId = 200.iid, grpId = 12345.grp)
            val zt =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 300.iid,
                    srcZoneId = ZoneIds.P1_LIBRARY,
                    destZoneId = ZoneIds.BATTLEFIELD,
                    category = "Resolve",
                )
            val rc = AnnotationBuilder.resolutionComplete(instanceId = 200.iid, grpId = 12345.grp)

            val input = listOf(rs, zt, rc)
            val result = AnnotationOrderEnforcer.enforce(input)

            result shouldBe input
        }

        test("Rule 5: moves non-stack Resolve ZoneTransfer inside the RS/RC pair") {
            val rs = AnnotationBuilder.resolutionStart(instanceId = 200.iid, grpId = 12345.grp)
            val oic = AnnotationBuilder.objectIdChanged(origId = 250.iid, newId = 300.iid)
            val zt =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 300.iid,
                    srcZoneId = ZoneIds.P1_LIBRARY,
                    destZoneId = ZoneIds.BATTLEFIELD,
                    category = "Resolve",
                )
            val rc = AnnotationBuilder.resolutionComplete(instanceId = 200.iid, grpId = 12345.grp)

            val result = AnnotationOrderEnforcer.enforce(listOf(oic, zt, rs, rc))

            result.map { it.typeList.first() } shouldBe
                listOf(
                    AnnotationType.ResolutionStart,
                    AnnotationType.ObjectIdChanged,
                    AnnotationType.ZoneTransfer_af5a,
                    AnnotationType.ResolutionComplete,
                )
        }
    })
