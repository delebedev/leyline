package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class AnnotationOrderEnforcerTest :
    FunSpec({

        tags(UnitTag)

        test("no-op when already ordered: ObjectIdChanged before ZoneTransfer") {
            val oic = AnnotationBuilder.objectIdChanged(origId = 100, newId = 200)
            val zt = AnnotationBuilder.zoneTransfer(
                instanceId = 200,
                srcZoneId = 31,
                destZoneId = 28,
                category = "PlayLand",
            )
            val uat = AnnotationBuilder.userActionTaken(instanceId = 200, seatId = 1, actionType = 3)

            val result = AnnotationOrderEnforcer.enforce(listOf(oic, zt, uat))

            result.map { it.typeList.first() } shouldBe listOf(
                AnnotationType.ObjectIdChanged,
                AnnotationType.ZoneTransfer_af5a,
                AnnotationType.UserActionTaken,
            )
        }

        test("reorders when ZoneTransfer precedes ObjectIdChanged") {
            val oic = AnnotationBuilder.objectIdChanged(origId = 100, newId = 200)
            val zt = AnnotationBuilder.zoneTransfer(
                instanceId = 200,
                srcZoneId = 31,
                destZoneId = 28,
                category = "CastSpell",
            )
            // Deliberately wrong order: ZT before OIC
            val result = AnnotationOrderEnforcer.enforce(listOf(zt, oic))

            result.map { it.typeList.first() } shouldBe listOf(
                AnnotationType.ObjectIdChanged,
                AnnotationType.ZoneTransfer_af5a,
            )
        }

        test("no-op when no ObjectIdChanged present") {
            val zt = AnnotationBuilder.zoneTransfer(
                instanceId = 200,
                srcZoneId = 27,
                destZoneId = 28,
                category = "Resolve",
            )
            val rs = AnnotationBuilder.resolutionStart(instanceId = 200, grpId = 12345)
            val rc = AnnotationBuilder.resolutionComplete(instanceId = 200, grpId = 12345)

            val input = listOf(rs, rc, zt)
            val result = AnnotationOrderEnforcer.enforce(input)

            // No ObjectIdChanged means no reordering needed
            result shouldBe input
        }

        test("handles multiple ObjectIdChanged for different cards") {
            val oic1 = AnnotationBuilder.objectIdChanged(origId = 100, newId = 200)
            val oic2 = AnnotationBuilder.objectIdChanged(origId = 300, newId = 400)
            val zt1 = AnnotationBuilder.zoneTransfer(
                instanceId = 200,
                srcZoneId = 31,
                destZoneId = 28,
                category = "PlayLand",
            )
            val zt2 = AnnotationBuilder.zoneTransfer(
                instanceId = 400,
                srcZoneId = 31,
                destZoneId = 27,
                category = "CastSpell",
            )

            // Both correctly ordered
            val result = AnnotationOrderEnforcer.enforce(listOf(oic1, zt1, oic2, zt2))
            result.map { it.typeList.first() } shouldBe listOf(
                AnnotationType.ObjectIdChanged,
                AnnotationType.ZoneTransfer_af5a,
                AnnotationType.ObjectIdChanged,
                AnnotationType.ZoneTransfer_af5a,
            )
        }

        test("fixes interleaved wrong order with multiple cards") {
            val oic1 = AnnotationBuilder.objectIdChanged(origId = 100, newId = 200)
            val oic2 = AnnotationBuilder.objectIdChanged(origId = 300, newId = 400)
            val zt1 = AnnotationBuilder.zoneTransfer(
                instanceId = 200,
                srcZoneId = 31,
                destZoneId = 28,
                category = "PlayLand",
            )
            val zt2 = AnnotationBuilder.zoneTransfer(
                instanceId = 400,
                srcZoneId = 31,
                destZoneId = 27,
                category = "CastSpell",
            )

            // Wrong: both ZTs before their OICs
            val result = AnnotationOrderEnforcer.enforce(listOf(zt1, zt2, oic1, oic2))

            // OIC1 should precede ZT1, OIC2 should precede ZT2
            val oic1Idx = result.indexOfFirst {
                it.typeList.first() == AnnotationType.ObjectIdChanged &&
                    it.affectedIdsList.contains(100)
            }
            val zt1Idx = result.indexOfFirst {
                it.typeList.first() == AnnotationType.ZoneTransfer_af5a &&
                    it.affectedIdsList.contains(200)
            }
            val oic2Idx = result.indexOfFirst {
                it.typeList.first() == AnnotationType.ObjectIdChanged &&
                    it.affectedIdsList.contains(300)
            }
            val zt2Idx = result.indexOfFirst {
                it.typeList.first() == AnnotationType.ZoneTransfer_af5a &&
                    it.affectedIdsList.contains(400)
            }

            (oic1Idx < zt1Idx) shouldBe true
            (oic2Idx < zt2Idx) shouldBe true
        }

        test("affectorId reference also triggers reorder") {
            val oic = AnnotationBuilder.objectIdChanged(origId = 100, newId = 200)
            val ann = AnnotationBuilder.userActionTaken(
                instanceId = 200,
                seatId = 1,
                actionType = 1,
            )

            // Wrong order: UAT (which has affectedIds containing 200) before OIC
            val result = AnnotationOrderEnforcer.enforce(listOf(ann, oic))

            result.map { it.typeList.first() } shouldBe listOf(
                AnnotationType.ObjectIdChanged,
                AnnotationType.UserActionTaken,
            )
        }

        // ===== Rule 2: Same-card incremental chaining =====

        test("Rule 2: DamageDealt before LayeredEffectCreated on same card") {
            val cardId = 500
            val damage = AnnotationBuilder.damageDealt(sourceInstanceId = 100, targetId = cardId, amount = 3)
            val effect = AnnotationBuilder.layeredEffectCreated(effectId = 7001, affectorId = cardId)

            // Correct order
            val result = AnnotationOrderEnforcer.enforce(listOf(damage, effect))
            result.map { it.typeList.first() } shouldBe listOf(
                AnnotationType.DamageDealt_af5a,
                AnnotationType.LayeredEffectCreated,
            )
        }

        test("Rule 2: reorders LayeredEffectCreated before DamageDealt on same card") {
            val cardId = 500
            val damage = AnnotationBuilder.damageDealt(sourceInstanceId = 100, targetId = cardId, amount = 3)
            val effect = AnnotationBuilder.layeredEffectCreated(effectId = 7001, affectorId = cardId)

            // Wrong order: effect before damage
            val result = AnnotationOrderEnforcer.enforce(listOf(effect, damage))
            result.map { it.typeList.first() } shouldBe listOf(
                AnnotationType.DamageDealt_af5a,
                AnnotationType.LayeredEffectCreated,
            )
        }

        test("Rule 2: CounterAdded before LayeredEffectCreated on same card") {
            val cardId = 500
            val counter = AnnotationBuilder.counterAdded(instanceId = cardId, counterType = "+1/+1", amount = 1)
            val effect = AnnotationBuilder.layeredEffectCreated(effectId = 7002, affectorId = cardId)

            // Wrong order
            val result = AnnotationOrderEnforcer.enforce(listOf(effect, counter))
            result.map { it.typeList.first() } shouldBe listOf(
                AnnotationType.CounterAdded,
                AnnotationType.LayeredEffectCreated,
            )
        }

        test("Rule 2: no-op when annotations affect different cards") {
            val damage = AnnotationBuilder.damageDealt(sourceInstanceId = 100, targetId = 500, amount = 3)
            val effect = AnnotationBuilder.layeredEffectCreated(effectId = 7001, affectorId = 600)

            // Different cards — no constraint, original order preserved
            val input = listOf(effect, damage)
            val result = AnnotationOrderEnforcer.enforce(input)
            result shouldBe input
        }

        test("Rule 2: ControllerChanged before TappedUntapped on same card") {
            val cardId = 500
            val tap = AnnotationBuilder.tappedUntappedPermanent(permanentId = cardId, abilityId = 501, tapped = true)
            val steal = AnnotationBuilder.controllerChanged(affectorId = 502, instanceId = cardId)

            // Wrong order: tap before steal
            val result = AnnotationOrderEnforcer.enforce(listOf(tap, steal))
            result.map { it.typeList.first() } shouldBe listOf(
                AnnotationType.ControllerChanged,
                AnnotationType.TappedUntappedPermanent,
            )
        }

        // ===== Rules 1 + 2 combined =====

        test("Rules 1+2: ObjectIdChanged + DamageDealt + LayeredEffectCreated") {
            val oic = AnnotationBuilder.objectIdChanged(origId = 100, newId = 500)
            val damage = AnnotationBuilder.damageDealt(sourceInstanceId = 200, targetId = 500, amount = 2)
            val effect = AnnotationBuilder.layeredEffectCreated(effectId = 7001, affectorId = 500)

            // Correct order
            val result = AnnotationOrderEnforcer.enforce(listOf(oic, damage, effect))
            result.map { it.typeList.first() } shouldBe listOf(
                AnnotationType.ObjectIdChanged,
                AnnotationType.DamageDealt_af5a,
                AnnotationType.LayeredEffectCreated,
            )
        }

        // ===== Stability =====

        test("unrelated annotations are not disturbed") {
            val oic = AnnotationBuilder.objectIdChanged(origId = 100, newId = 200)
            val zt = AnnotationBuilder.zoneTransfer(
                instanceId = 200,
                srcZoneId = 31,
                destZoneId = 28,
                category = "PlayLand",
            )
            // Unrelated annotation referencing a completely different ID
            val unrelated = AnnotationBuilder.tappedUntappedPermanent(permanentId = 500, abilityId = 501, tapped = true)

            val result = AnnotationOrderEnforcer.enforce(listOf(oic, unrelated, zt))

            // Unrelated stays between OIC and ZT (stable order preserved)
            result.map { it.typeList.first() } shouldBe listOf(
                AnnotationType.ObjectIdChanged,
                AnnotationType.TappedUntappedPermanent,
                AnnotationType.ZoneTransfer_af5a,
            )
        }
    })
