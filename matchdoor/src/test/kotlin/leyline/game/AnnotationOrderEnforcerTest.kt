package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.toWireId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationOrderEnforcer
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

            // No ObjectIdChanged means no reordering needed
            result shouldBe input
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

        // ===== Rule 5: ResolutionSandwich (saga chapter-III + transform) =====

        test("Rule 5: nests transform OIC/ZT pairs inside RS/RC bracket") {
            // Saga chapter-III + transform shape. Pre-rule:
            //   [OIC{372→417}, ZT{Exile, affected=417}, OIC{417→418}, ZT{Return, affected=418},
            //    RS{affector=416}, RC{affector=416}, AID{affected=416, affector=372}]
            // Target:
            //   [RS, OIC, ZT(Exile), OIC, ZT(Return), RC, AID]
            val oicExile = AnnotationBuilder.objectIdChanged(origId = 372.iid, newId = 417.iid)
            val ztExile =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 417.iid,
                    srcZoneId = 28,
                    destZoneId = 29,
                    category = "Exile",
                )
            val oicReturn = AnnotationBuilder.objectIdChanged(origId = 417.iid, newId = 418.iid)
            val ztReturn =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 418.iid,
                    srcZoneId = 29,
                    destZoneId = 28,
                    category = "Return",
                )
            val rs = AnnotationBuilder.resolutionStart(instanceId = 416.iid, grpId = 12345.grp)
            val rc = AnnotationBuilder.resolutionComplete(instanceId = 416.iid, grpId = 12345.grp)
            val aid = AnnotationBuilder.abilityInstanceDeleted(abilityInstanceId = 416.iid, affectorId = 372.iid)

            val result = AnnotationOrderEnforcer.enforce(listOf(oicExile, ztExile, oicReturn, ztReturn, rs, rc, aid))

            // Final shape: [RS, OIC(372→417), ZT(Exile), OIC(417→418), ZT(Return), RC, AID]
            val types = result.map { it.typeList.first() }
            types shouldBe
                listOf(
                    AnnotationType.ResolutionStart,
                    AnnotationType.ObjectIdChanged,
                    AnnotationType.ZoneTransfer_af5a,
                    AnnotationType.ObjectIdChanged,
                    AnnotationType.ZoneTransfer_af5a,
                    AnnotationType.ResolutionComplete,
                    AnnotationType.AbilityInstanceDeleted,
                )
        }

        test("Rule 5: no-op when no transforms (simple resolve)") {
            // Simple no-transform resolve. Already in correct order, nothing for the rule to do.
            val rs = AnnotationBuilder.resolutionStart(instanceId = 416.iid, grpId = 12345.grp)
            val zt =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 416.iid,
                    srcZoneId = 27,
                    destZoneId = 28,
                    category = "Resolve",
                )
            val rc = AnnotationBuilder.resolutionComplete(instanceId = 416.iid, grpId = 12345.grp)
            val aid = AnnotationBuilder.abilityInstanceDeleted(abilityInstanceId = 416.iid, affectorId = 372.iid)

            val input = listOf(rs, zt, rc, aid)
            val result = AnnotationOrderEnforcer.enforce(input)

            result shouldBe input
        }

        test("Rule 5: no-op when no AID (cannot identify bracket source)") {
            // Without an AID matching the RS, the rule has no source iid lineage start.
            val rs = AnnotationBuilder.resolutionStart(instanceId = 416.iid, grpId = 12345.grp)
            val rc = AnnotationBuilder.resolutionComplete(instanceId = 416.iid, grpId = 12345.grp)
            val oic = AnnotationBuilder.objectIdChanged(origId = 100.iid, newId = 200.iid)
            val zt =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 200.iid,
                    srcZoneId = 28,
                    destZoneId = 29,
                    category = "Exile",
                )

            // Already in valid (rule-1) order: OIC before ZT; RS/RC at front.
            val input = listOf(rs, rc, oic, zt)
            val result = AnnotationOrderEnforcer.enforce(input)

            result shouldBe input
        }

        // ===== Rule 6: DamageBeforeDeath =====

        test("Rule 6: reorders DamageDealt before OIC and lethal ZT for the victim iid") {
            // Pre-rule (wrong): [OIC{100→101}, ZT(SBA_Damage, affected=101), DamageDealt(victim=100)]
            // Post: DamageDealt must precede both OIC and ZT.
            val oic = AnnotationBuilder.objectIdChanged(origId = 100.iid, newId = 101.iid)
            val zt =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 101.iid,
                    srcZoneId = 28,
                    destZoneId = 29,
                    category = "SBA_Damage",
                )
            val damage = AnnotationBuilder.damageDealt(sourceInstanceId = 50.iid, targetId = 100.wid, amount = 3)

            val result = AnnotationOrderEnforcer.enforce(listOf(oic, zt, damage))

            val damageIdx = result.indexOfFirst { AnnotationType.DamageDealt_af5a in it.typeList }
            val oicIdx = result.indexOfFirst { AnnotationType.ObjectIdChanged in it.typeList }
            val ztIdx = result.indexOfFirst { AnnotationType.ZoneTransfer_af5a in it.typeList }

            damageIdx shouldBeLessThan oicIdx
            damageIdx shouldBeLessThan ztIdx
        }

        test("Rule 6: no-op when ZT is not in a lethal category") {
            // ZT is for a different iid (200/201) and category Resolve — not lethal for victim 100.
            val damage = AnnotationBuilder.damageDealt(sourceInstanceId = 50.iid, targetId = 100.wid, amount = 3)
            val oic = AnnotationBuilder.objectIdChanged(origId = 200.iid, newId = 201.iid)
            val zt =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 201.iid,
                    srcZoneId = 27,
                    destZoneId = 28,
                    category = "Resolve",
                )

            val input = listOf(damage, oic, zt)
            val result = AnnotationOrderEnforcer.enforce(input)

            result shouldBe input
        }

        test("Rule 6: no-op when lethal ZT is for a different iid") {
            // DamageDealt victim is 100; lethal ZT is for iid 201 (unrelated). No edge.
            val damage = AnnotationBuilder.damageDealt(sourceInstanceId = 50.iid, targetId = 100.wid, amount = 3)
            val oic = AnnotationBuilder.objectIdChanged(origId = 200.iid, newId = 201.iid)
            val zt =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 201.iid,
                    srcZoneId = 28,
                    destZoneId = 29,
                    category = "SBA_Damage",
                )

            val input = listOf(damage, oic, zt)
            val result = AnnotationOrderEnforcer.enforce(input)

            result shouldBe input
        }

        // ===== Rule 7: CombatDamageBlock =====

        test("Rule 7: reorders combat damage block per ladder") {
            // Pre-rule (wrong): [ZT(SBA_Damage), OIC, DamageDealt, PoSM(combat,CombatDamage), ModifiedLife]
            // PhaseFirst rule pulls PoSM to index 0.
            // Rule 7 enforces ladder DamageDealt → ModifiedLife → OIC → ZT for CombatDamage GSMs.
            val zt =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 101.iid,
                    srcZoneId = 28,
                    destZoneId = 29,
                    category = "SBA_Damage",
                )
            val oic = AnnotationBuilder.objectIdChanged(origId = 100.iid, newId = 101.iid)
            val damage = AnnotationBuilder.damageDealt(sourceInstanceId = 50.iid, targetId = 100.wid, amount = 3)
            val posm =
                AnnotationBuilder.phaseOrStepModified(
                    activeSeat = 1.sid,
                    phase = 3, // Phase.Combat_a549.number
                    step = 7, // Step.CombatDamage_a2cb.number
                )
            val life = AnnotationBuilder.modifiedLife(playerSeatId = 2.sid, lifeDelta = -3)

            val result = AnnotationOrderEnforcer.enforce(listOf(zt, oic, damage, posm, life))

            result.map { it.typeList.first() } shouldBe
                listOf(
                    AnnotationType.PhaseOrStepModified,
                    AnnotationType.DamageDealt_af5a,
                    AnnotationType.ModifiedLife,
                    AnnotationType.ObjectIdChanged,
                    AnnotationType.ZoneTransfer_af5a,
                )
        }

        test("Rule 7: no-op outside CombatDamage step") {
            // PoSM present but not Combat/CombatDamage — Rule 7 emits no edges.
            // Rule 1 would still fire, but here the OIC is already before ZT for the same iid.
            val damage = AnnotationBuilder.damageDealt(sourceInstanceId = 50.iid, targetId = 100.wid, amount = 3)
            val oic = AnnotationBuilder.objectIdChanged(origId = 200.iid, newId = 201.iid)
            val zt =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 201.iid,
                    srcZoneId = 27,
                    destZoneId = 28,
                    category = "Resolve",
                )

            // No PoSM at all → Rule 7 does not fire.
            val input = listOf(oic, damage, zt)
            val result = AnnotationOrderEnforcer.enforce(input)

            result shouldBe input
        }

        test("Rule 7: gap-tolerant — handles missing intermediate ladder types") {
            // Combat damage GSM with only DamageDealt and ZT (no DamagedThisTurn, no
            // SyntheticEvent, no ModifiedLife, no LayeredEffectDestroyed, no OIC, no AID).
            // Rule 7 emits a direct DamageDealt → ZT edge so the ladder still holds.
            val posm =
                AnnotationBuilder.phaseOrStepModified(
                    activeSeat = 1.sid,
                    phase = 3,
                    step = 7,
                )
            val damage = AnnotationBuilder.damageDealt(sourceInstanceId = 50.iid, targetId = 100.wid, amount = 3)
            val zt =
                AnnotationBuilder.zoneTransfer(
                    instanceId = 100.iid,
                    srcZoneId = 28,
                    destZoneId = 29,
                    category = "SBA_Damage",
                )

            // Wrong order: ZT before DamageDealt.
            val result = AnnotationOrderEnforcer.enforce(listOf(posm, zt, damage))

            result.map { it.typeList.first() } shouldBe
                listOf(
                    AnnotationType.PhaseOrStepModified,
                    AnnotationType.DamageDealt_af5a,
                    AnnotationType.ZoneTransfer_af5a,
                )
        }
    })
