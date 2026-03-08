package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType

class AnnotationBuilderTest :
    FunSpec({

        tags(UnitTag)

        test("zoneTransferAnnotation") {
            val ann = AnnotationBuilder.zoneTransfer(
                instanceId = 100,
                srcZoneId = 31, // Hand
                destZoneId = 28, // Battlefield
                category = "PlayLand",
            )
            ann.typeList shouldContain AnnotationType.ZoneTransfer_af5a
            // zone_src/zone_dest use Int32 type (matches real recordings)
            val zoneSrc = ann.detailsList.first { it.key == "zone_src" }
            zoneSrc.getValueInt32(0) shouldBe 31
            val zoneDest = ann.detailsList.first { it.key == "zone_dest" }
            zoneDest.getValueInt32(0) shouldBe 28
            val category = ann.detailsList.first { it.key == "category" }
            category.getValueString(0) shouldBe "PlayLand"
            ann.affectedIdsList shouldContain 100
        }

        test("castSpellAnnotation") {
            val ann = AnnotationBuilder.zoneTransfer(
                instanceId = 105,
                srcZoneId = 31, // Hand
                destZoneId = 27, // Stack
                category = "CastSpell",
            )
            val zoneSrc = ann.detailsList.first { it.key == "zone_src" }
            zoneSrc.getValueInt32(0) shouldBe 31
            val zoneDest = ann.detailsList.first { it.key == "zone_dest" }
            zoneDest.getValueInt32(0) shouldBe 27
            val category = ann.detailsList.first { it.key == "category" }
            category.getValueString(0) shouldBe "CastSpell"
        }

        test("zoneTransferWithActingSeat") {
            val ann = AnnotationBuilder.zoneTransfer(
                instanceId = 200,
                srcZoneId = 27,
                destZoneId = 28,
                category = "Resolve",
                actingSeatId = 1,
            )
            ann.affectorId shouldBe 1
            ann.affectedIdsList shouldContain 200
        }

        test("zoneTransferWithoutActingSeatHasZeroAffector") {
            val ann = AnnotationBuilder.zoneTransfer(
                instanceId = 200,
                srcZoneId = 31,
                destZoneId = 28,
                category = "PlayLand",
            )
            ann.affectorId shouldBe 0
        }

        // --- ObjectIdChanged ---

        test("objectIdChangedHasOrigAndNewId") {
            val ann = AnnotationBuilder.objectIdChanged(origId = 100, newId = 150)
            ann.typeList shouldContain AnnotationType.ObjectIdChanged
            ann.affectedIdsList shouldContain 100

            val origDetail = ann.detailsList.first { it.key == "orig_id" }
            origDetail.type shouldBe KeyValuePairValueType.Int32
            origDetail.getValueInt32(0) shouldBe 100

            val newDetail = ann.detailsList.first { it.key == "new_id" }
            newDetail.type shouldBe KeyValuePairValueType.Int32
            newDetail.getValueInt32(0) shouldBe 150
        }

        test("objectIdChangedNoAffectorId") {
            val ann = AnnotationBuilder.objectIdChanged(origId = 100, newId = 200)
            ann.affectorId shouldBe 0
        }

        test("objectIdChangedWithAffectorId") {
            val ann = AnnotationBuilder.objectIdChanged(origId = 100, newId = 200, affectorId = 500)
            ann.affectorId shouldBe 500
            ann.affectedIdsList shouldContain 100
        }

        // --- ZoneTransfer affectorId ---

        test("zoneTransferWithAffectorId") {
            val ann = AnnotationBuilder.zoneTransfer(
                instanceId = 200,
                srcZoneId = 32,
                destZoneId = 33,
                category = "Surveil",
                affectorId = 500,
            )
            ann.affectorId shouldBe 500
            ann.affectedIdsList shouldContain 200
        }

        test("zoneTransferAffectorIdTakesPrecedenceOverActingSeat") {
            val ann = AnnotationBuilder.zoneTransfer(
                instanceId = 200,
                srcZoneId = 32,
                destZoneId = 33,
                category = "Surveil",
                actingSeatId = 1,
                affectorId = 500,
            )
            // affectorId (ability instance) takes precedence over actingSeatId (player seat)
            ann.affectorId shouldBe 500
        }

        // --- UserActionTaken ---

        test("userActionTakenFields") {
            val ann = AnnotationBuilder.userActionTaken(
                instanceId = 300,
                seatId = 1,
                actionType = 3,
                abilityGrpId = 0,
            )
            ann.typeList shouldContain AnnotationType.UserActionTaken
            ann.affectorId shouldBe 1
            ann.affectedIdsList shouldContain 300

            val actionType = ann.detailsList.first { it.key == "actionType" }
            actionType.type shouldBe KeyValuePairValueType.Int32
            actionType.getValueInt32(0) shouldBe 3

            val abilityGrpId = ann.detailsList.first { it.key == "abilityGrpId" }
            abilityGrpId.type shouldBe KeyValuePairValueType.Int32
            abilityGrpId.getValueInt32(0) shouldBe 0
        }

        test("userActionTakenCastType") {
            val ann = AnnotationBuilder.userActionTaken(instanceId = 400, seatId = 2, actionType = 1)
            val actionType = ann.detailsList.first { it.key == "actionType" }
            actionType.getValueInt32(0) shouldBe 1
            ann.affectorId shouldBe 2
        }

        // --- ResolutionStart ---

        test("resolutionStartFields") {
            val ann = AnnotationBuilder.resolutionStart(instanceId = 500, grpId = 12345)
            ann.typeList shouldContain AnnotationType.ResolutionStart
            ann.affectorId shouldBe 500
            ann.affectedIdsList shouldContain 500

            val grpid = ann.detailsList.first { it.key == "grpid" }
            grpid.type shouldBe KeyValuePairValueType.Uint32
            grpid.getValueUint32(0) shouldBe 12345
        }

        // --- ResolutionComplete ---

        test("resolutionCompleteFields") {
            val ann = AnnotationBuilder.resolutionComplete(instanceId = 500, grpId = 12345)
            ann.typeList shouldContain AnnotationType.ResolutionComplete
            ann.affectorId shouldBe 500
            ann.affectedIdsList shouldContain 500

            val grpid = ann.detailsList.first { it.key == "grpid" }
            grpid.type shouldBe KeyValuePairValueType.Uint32
            grpid.getValueUint32(0) shouldBe 12345
        }

        // --- PhaseOrStepModified ---

        test("phaseOrStepModifiedHasContent") {
            val ann = AnnotationBuilder.phaseOrStepModified(activeSeat = 2, phase = 1, step = 2)
            ann.typeList shouldContain AnnotationType.PhaseOrStepModified
            ann.affectedIdsList shouldContain 2
            val detailKeys = ann.detailsList.map { it.key }.toSet()
            detailKeys shouldContain "phase"
            detailKeys shouldContain "step"
        }

        // --- ManaPaid ---

        test("manaPaidFields") {
            val ann = AnnotationBuilder.manaPaid(instanceId = 600, manaId = 5, color = "Green")
            ann.typeList shouldContain AnnotationType.ManaPaid
            ann.affectedIdsList shouldContain 600
            ann.affectorId shouldBe 0

            val id = ann.detailsList.first { it.key == "id" }
            id.type shouldBe KeyValuePairValueType.Int32
            id.getValueInt32(0) shouldBe 5

            val color = ann.detailsList.first { it.key == "color" }
            color.type shouldBe KeyValuePairValueType.String
            color.getValueString(0) shouldBe "Green"
        }

        test("manaPaidDefaults") {
            val ann = AnnotationBuilder.manaPaid(instanceId = 600)
            // Defaults: manaId=0, color=""
            val id = ann.detailsList.first { it.key == "id" }
            id.getValueInt32(0) shouldBe 0
            val color = ann.detailsList.first { it.key == "color" }
            color.getValueString(0) shouldBe ""
        }

        // --- TappedUntappedPermanent ---

        test("tappedUntappedPermanentFields") {
            val ann = AnnotationBuilder.tappedUntappedPermanent(permanentId = 700, abilityId = 800)
            ann.typeList shouldContain AnnotationType.TappedUntappedPermanent
            ann.affectorId shouldBe 800
            ann.affectedIdsList shouldContain 700

            val tapped = ann.detailsList.first { it.key == "tapped" }
            tapped.type shouldBe KeyValuePairValueType.Uint32
            tapped.getValueUint32(0) shouldBe 1
        }

        test("tappedUntappedPermanentUntapVariant") {
            val ann = AnnotationBuilder.tappedUntappedPermanent(permanentId = 700, abilityId = 800, tapped = false)
            ann.typeList shouldContain AnnotationType.TappedUntappedPermanent
            ann.affectorId shouldBe 800
            ann.affectedIdsList shouldContain 700

            val tapped = ann.detailsList.first { it.key == "tapped" }
            tapped.getValueUint32(0) shouldBe 0
        }

        // --- AbilityInstanceCreated ---

        test("abilityInstanceCreatedFields") {
            val ann = AnnotationBuilder.abilityInstanceCreated(instanceId = 900, sourceZoneId = 31)
            ann.typeList shouldContain AnnotationType.AbilityInstanceCreated
            ann.affectedIdsList shouldContain 900
            ann.affectorId shouldBe 0

            val srcZone = ann.detailsList.first { it.key == "source_zone" }
            srcZone.type shouldBe KeyValuePairValueType.Int32
            srcZone.getValueInt32(0) shouldBe 31
        }

        test("abilityInstanceCreatedDefaultZone") {
            val ann = AnnotationBuilder.abilityInstanceCreated(instanceId = 900)
            val srcZone = ann.detailsList.first { it.key == "source_zone" }
            srcZone.getValueInt32(0) shouldBe 0
        }

        // --- AbilityInstanceDeleted ---

        test("abilityInstanceDeletedFields") {
            val ann = AnnotationBuilder.abilityInstanceDeleted(instanceId = 900)
            ann.typeList shouldContain AnnotationType.AbilityInstanceDeleted
            ann.affectedIdsList shouldContain 900
            ann.affectorId shouldBe 0
        }

        // --- EnteredZoneThisTurn ---

        test("enteredZoneThisTurnFields") {
            val ann = AnnotationBuilder.enteredZoneThisTurn(zoneId = ZoneIds.BATTLEFIELD, 100, 200)
            ann.typeList shouldContain AnnotationType.EnteredZoneThisTurn
            ann.affectorId shouldBe 28
            ann.affectedIdsList shouldContain 100
            ann.affectedIdsList shouldContain 200
            ann.affectedIdsCount shouldBe 2
        }

        test("enteredZoneThisTurnSingleId") {
            val ann = AnnotationBuilder.enteredZoneThisTurn(zoneId = ZoneIds.BATTLEFIELD, 100)
            ann.affectedIdsCount shouldBe 1
            ann.affectedIdsList shouldContain 100
        }

        // --- DamageDealt ---

        test("damageDealtFields") {
            val ann = AnnotationBuilder.damageDealt(sourceInstanceId = 1000, amount = 3)
            ann.typeList shouldContain AnnotationType.DamageDealt_af5a
            ann.affectedIdsList shouldContain 1000

            val damage = ann.detailsList.first { it.key == "damage" }
            damage.type shouldBe KeyValuePairValueType.Uint32
            damage.getValueUint32(0) shouldBe 3

            // type defaults to 1 (combat)
            val type = ann.detailsList.first { it.key == "type" }
            type.type shouldBe KeyValuePairValueType.Uint32
            type.getValueUint32(0) shouldBe 1

            // markDamage defaults to amount
            val markDamage = ann.detailsList.first { it.key == "markDamage" }
            markDamage.type shouldBe KeyValuePairValueType.Uint32
            markDamage.getValueUint32(0) shouldBe 3
        }

        test("damageDealtNonCombat") {
            val ann = AnnotationBuilder.damageDealt(sourceInstanceId = 1000, amount = 2, type = 0, markDamage = 2)
            val type = ann.detailsList.first { it.key == "type" }
            type.getValueUint32(0) shouldBe 0
        }

        // --- ModifiedLife ---

        test("modifiedLifePositiveDelta") {
            val ann = AnnotationBuilder.modifiedLife(playerSeatId = 1, lifeDelta = 3)
            ann.typeList shouldContain AnnotationType.ModifiedLife
            ann.affectedIdsList shouldContain 1

            val life = ann.detailsList.first { it.key == "life" }
            life.type shouldBe KeyValuePairValueType.Int32
            life.getValueInt32(0) shouldBe 3
        }

        test("modifiedLifeNegativeDelta") {
            val ann = AnnotationBuilder.modifiedLife(playerSeatId = 2, lifeDelta = -5)
            val life = ann.detailsList.first { it.key == "life" }
            life.getValueInt32(0) shouldBe -5
        }

        // --- SyntheticEvent ---

        test("syntheticEventFields") {
            val ann = AnnotationBuilder.syntheticEvent(seatId = 1)
            ann.typeList shouldContain AnnotationType.SyntheticEvent
            ann.affectedIdsList shouldContain 1
            val type = ann.detailsList.first { it.key == "type" }
            type.type shouldBe KeyValuePairValueType.Uint32
            type.getValueUint32(0) shouldBe 1
        }

        // --- TokenCreated (Group B) ---

        test("tokenCreatedFields") {
            val ann = AnnotationBuilder.tokenCreated(instanceId = 1100)
            ann.typeList shouldContain AnnotationType.TokenCreated
            ann.affectedIdsList shouldContain 1100
            ann.affectorId shouldBe 0
            ann.detailsCount shouldBe 0
        }

        // --- TokenDeleted (Group B) ---

        test("tokenDeletedFields") {
            val ann = AnnotationBuilder.tokenDeleted(instanceId = 1150)
            ann.typeList shouldContain AnnotationType.TokenDeleted
            ann.affectorId shouldBe 1150
            ann.affectedIdsList shouldContain 1150
            ann.affectedIdsCount shouldBe 1
            ann.detailsCount shouldBe 0
        }

        // --- CounterAdded (Group B) ---

        test("counterAddedFields") {
            val ann = AnnotationBuilder.counterAdded(instanceId = 100, counterType = "P1P1", amount = 2)
            ann.typeList shouldContain AnnotationType.CounterAdded
            ann.affectedIdsList shouldContain 100

            val type = ann.detailsList.first { it.key == "counter_type" }
            type.type shouldBe KeyValuePairValueType.String
            type.getValueString(0) shouldBe "P1P1"

            val txn = ann.detailsList.first { it.key == "transaction_amount" }
            txn.type shouldBe KeyValuePairValueType.Int32
            txn.getValueInt32(0) shouldBe 2
        }

        // --- CounterRemoved (Group B) ---

        test("counterRemovedFields") {
            val ann = AnnotationBuilder.counterRemoved(instanceId = 200, counterType = "LOYALTY", amount = 3)
            ann.typeList shouldContain AnnotationType.CounterRemoved
            ann.affectedIdsList shouldContain 200

            val type = ann.detailsList.first { it.key == "counter_type" }
            type.getValueString(0) shouldBe "LOYALTY"

            val txn = ann.detailsList.first { it.key == "transaction_amount" }
            txn.getValueInt32(0) shouldBe 3
        }

        // --- Shuffle (Group B) ---

        test("shuffleFields") {
            val ann = AnnotationBuilder.shuffle(seatId = 1)
            ann.typeList shouldContain AnnotationType.Shuffle
            ann.affectedIdsList shouldContain 1
        }

        // --- ModifiedPower (Group B) ---

        test("modifiedPowerFields") {
            val ann = AnnotationBuilder.modifiedPower(instanceId = 1200)
            ann.typeList shouldContain AnnotationType.ModifiedPower
            ann.affectedIdsList shouldContain 1200
            ann.affectorId shouldBe 0
            ann.detailsCount shouldBe 0
        }

        // --- ModifiedToughness (Group B) ---

        test("modifiedToughnessFields") {
            val ann = AnnotationBuilder.modifiedToughness(instanceId = 1300)
            ann.typeList shouldContain AnnotationType.ModifiedToughness
            ann.affectedIdsList shouldContain 1300
            ann.detailsCount shouldBe 0
        }

        // --- RemoveAttachment (Group A+) ---

        test("removeAttachmentFields") {
            val ann = AnnotationBuilder.removeAttachment(auraIid = 1400)
            ann.typeList shouldContain AnnotationType.RemoveAttachment
            ann.affectedIdsList shouldContain 1400
            ann.affectedIdsCount shouldBe 1
        }

        // --- AttachmentCreated (Group A+) ---

        test("attachmentCreatedFields") {
            val ann = AnnotationBuilder.attachmentCreated(auraIid = 1500, targetIid = 1600)
            ann.typeList shouldContain AnnotationType.AttachmentCreated
            ann.affectorId shouldBe 0
            ann.affectedIdsList shouldBe listOf(1500, 1600)
        }

        // --- Attachment (Group A+ persistent) ---

        test("attachmentFields") {
            val ann = AnnotationBuilder.attachment(auraIid = 1500, targetIid = 1600)
            ann.typeList shouldContain AnnotationType.Attachment
            ann.affectorId shouldBe 0
            ann.affectedIdsList shouldBe listOf(1500, 1600)
        }

        // --- Scry (Group B) ---

        test("scryFields") {
            val ann = AnnotationBuilder.scry(seatId = 1, topCount = 2, bottomCount = 1)
            ann.typeList shouldContain AnnotationType.Scry_af5a
            ann.affectedIdsList shouldContain 1

            val top = ann.detailsList.first { it.key == "topCount" }
            top.type shouldBe KeyValuePairValueType.Int32
            top.getValueInt32(0) shouldBe 2

            val bottom = ann.detailsList.first { it.key == "bottomCount" }
            bottom.getValueInt32(0) shouldBe 1
        }

        // --- Counter State (Tier 1) ---

        test("counterStateFields") {
            val ann = AnnotationBuilder.counter(instanceId = 100, counterType = 1, count = 1)
            ann.typeList shouldContain AnnotationType.Counter_803b
            ann.affectedIdsList shouldContain 100
            val count = ann.detailsList.first { it.key == "count" }
            count.type shouldBe KeyValuePairValueType.Int32
            count.getValueInt32(0) shouldBe 1
            val type = ann.detailsList.first { it.key == "counter_type" }
            type.getValueInt32(0) shouldBe 1
        }

        test("counterTypeIdMapsForgeNames") {
            // Exact matches (P1P1, M1M1 already uppercase in both)
            AnnotationBuilder.counterTypeId("P1P1") shouldBe 1
            AnnotationBuilder.counterTypeId("M1M1") shouldBe 2
            // Forge UPPERCASE → proto PascalCase
            AnnotationBuilder.counterTypeId("LOYALTY") shouldBe 7
            AnnotationBuilder.counterTypeId("CHARGE") shouldBe 19
            AnnotationBuilder.counterTypeId("AGE") shouldBe 9
            AnnotationBuilder.counterTypeId("BLOOD") shouldBe 15
            AnnotationBuilder.counterTypeId("STUN") shouldBe 172
            AnnotationBuilder.counterTypeId("POISON") shouldBe 3
            // Unknown falls back to 0
            AnnotationBuilder.counterTypeId("NONEXISTENT") shouldBe 0
        }

        // --- AddAbility (Tier 1) ---

        test("addAbilityFields") {
            val ann = AnnotationBuilder.addAbility(
                instanceId = 100,
                grpId = 6,
                effectId = 7005,
                uniqueAbilityId = 217,
                originalAbilityObjectZcid = 372,
            )
            ann.typeList shouldContain AnnotationType.AddAbility_af5a
            ann.affectedIdsList shouldContain 100
            ann.detailsList.first { it.key == "grpid" }.getValueInt32(0) shouldBe 6
            ann.detailsList.first { it.key == "effect_id" }.getValueInt32(0) shouldBe 7005
            ann.detailsList.first { it.key == "UniqueAbilityId" }.getValueInt32(0) shouldBe 217
            ann.detailsList.first { it.key == "originalAbilityObjectZcid" }.getValueInt32(0) shouldBe 372
        }

        // --- RemoveAbility (Tier 1) ---

        test("removeAbilityFields") {
            val ann = AnnotationBuilder.removeAbility(instanceId = 200, effectId = 7003)
            ann.typeList shouldContain AnnotationType.RemoveAbility
            ann.affectedIdsList shouldContain 200
            ann.detailsList.first { it.key == "effect_id" }.getValueInt32(0) shouldBe 7003
            ann.detailsCount shouldBe 1
        }

        // --- AbilityExhausted (Tier 1) ---

        test("abilityExhaustedFields") {
            val ann = AnnotationBuilder.abilityExhausted(
                instanceId = 294,
                abilityGrpId = 137955,
                usesRemaining = 0,
                uniqueAbilityId = 205,
            )
            ann.typeList shouldContain AnnotationType.AbilityExhausted
            ann.affectedIdsList shouldContain 294
            ann.detailsList.first { it.key == "AbilityGrpId" }.getValueInt32(0) shouldBe 137955
            ann.detailsList.first { it.key == "UsesRemaining" }.getValueInt32(0) shouldBe 0
            ann.detailsList.first { it.key == "UniqueAbilityId" }.getValueInt32(0) shouldBe 205
        }

        // --- GainDesignation (Tier 1) ---

        test("gainDesignationFields") {
            val ann = AnnotationBuilder.gainDesignation(seatId = 1, designationType = 19)
            ann.typeList shouldContain AnnotationType.GainDesignation
            ann.affectedIdsList shouldContain 1
            ann.detailsList.first { it.key == "DesignationType" }.getValueInt32(0) shouldBe 19
        }

        // --- Designation (Tier 1 stub) ---

        test("designationFields") {
            val ann = AnnotationBuilder.designation(seatId = 1, designationType = 19)
            ann.typeList shouldContain AnnotationType.Designation
            ann.affectedIdsList shouldContain 1
            ann.detailsList.first { it.key == "DesignationType" }.getValueInt32(0) shouldBe 19
        }

        // --- LayeredEffect (Tier 1 stub) ---

        test("layeredEffectFields") {
            val ann = AnnotationBuilder.layeredEffect(instanceId = 289, effectId = 7004)
            ann.typeList shouldContain AnnotationType.LayeredEffect
            ann.affectedIdsList shouldContain 289
            ann.detailsList.first { it.key == "effect_id" }.getValueInt32(0) shouldBe 7004
        }

        // --- LayeredEffectCreated ---

        test("layeredEffectCreated has correct type and affectedIds") {
            val ann = AnnotationBuilder.layeredEffectCreated(effectId = 7005)
            ann.typeList.first() shouldBe AnnotationType.LayeredEffectCreated
            ann.affectedIdsList shouldBe listOf(7005)
        }

        test("layeredEffectCreated with affectorId includes it") {
            val ann = AnnotationBuilder.layeredEffectCreated(effectId = 7005, affectorId = 335)
            ann.affectorId shouldBe 335
        }

        test("layeredEffectCreated without affectorId defaults to zero") {
            val ann = AnnotationBuilder.layeredEffectCreated(effectId = 7005)
            ann.affectorId shouldBe 0
        }

        test("layeredEffect P/T buff has multi-type array") {
            val ann = AnnotationBuilder.layeredEffect(
                instanceId = 100,
                effectId = 7005,
                powerDelta = 1,
                toughnessDelta = 1,
                affectorId = 100,
            )
            // Real server: [ModifiedToughness, ModifiedPower, LayeredEffect]
            ann.typeList shouldContain AnnotationType.ModifiedToughness
            ann.typeList shouldContain AnnotationType.ModifiedPower
            ann.typeList shouldContain AnnotationType.LayeredEffect
            ann.affectedIdsList shouldBe listOf(100)
            ann.affectorId shouldBe 100
            val effectIdDetail = ann.detailsList.first { it.key == "effect_id" }
            effectIdDetail.getValueInt32(0) shouldBe 7005
            // No LayeredEffectType for P/T buffs (real server only uses it for CopyObject)
            ann.detailsList.none { it.key == "LayeredEffectType" } shouldBe true
        }

        test("layeredEffect power-only has ModifiedPower co-type") {
            val ann = AnnotationBuilder.layeredEffect(instanceId = 100, effectId = 7005, powerDelta = 3)
            ann.typeList shouldContain AnnotationType.ModifiedPower
            ann.typeList shouldContain AnnotationType.LayeredEffect
            ann.typeList.none { it == AnnotationType.ModifiedToughness } shouldBe true
        }

        test("layeredEffect no deltas has LayeredEffect only") {
            val ann = AnnotationBuilder.layeredEffect(instanceId = 100, effectId = 7005)
            ann.typeList shouldBe listOf(AnnotationType.LayeredEffect)
            ann.affectedIdsList shouldBe listOf(100)
        }

        test("layeredEffect sourceAbilityGrpId included when set") {
            val ann = AnnotationBuilder.layeredEffect(
                instanceId = 100,
                effectId = 7007,
                sourceAbilityGrpId = 137,
                powerDelta = 1,
                toughnessDelta = 1,
            )
            val detail = ann.detailsList.first { it.key == "sourceAbilityGRPID" }
            detail.getValueInt32(0) shouldBe 137
        }

        test("powerToughnessModCreated has affectorId and detail keys") {
            val ann = AnnotationBuilder.powerToughnessModCreated(
                instanceId = 335,
                power = 1,
                toughness = 1,
                affectorId = 340,
            )
            ann.typeList shouldBe listOf(AnnotationType.PowerToughnessModCreated)
            ann.affectedIdsList shouldBe listOf(335)
            ann.affectorId shouldBe 340
            ann.detailsList.first { it.key == "power" }.getValueInt32(0) shouldBe 1
            ann.detailsList.first { it.key == "toughness" }.getValueInt32(0) shouldBe 1
        }

        // --- Detail-less Tier 2 ---

        test("layeredEffectDestroyedFields") {
            val ann = AnnotationBuilder.layeredEffectDestroyed(effectId = 7007)
            ann.typeList shouldContain AnnotationType.LayeredEffectDestroyed
            ann.affectedIdsList shouldContain 7007
            ann.detailsCount shouldBe 0
        }

        test("playerSelectingTargetsFields") {
            val ann = AnnotationBuilder.playerSelectingTargets(instanceId = 303)
            ann.typeList shouldContain AnnotationType.PlayerSelectingTargets
            ann.affectedIdsList shouldContain 303
            ann.detailsCount shouldBe 0
        }

        test("playerSubmittedTargetsFields") {
            val ann = AnnotationBuilder.playerSubmittedTargets(instanceId = 303)
            ann.typeList shouldContain AnnotationType.PlayerSubmittedTargets
            ann.affectedIdsList shouldContain 303
            ann.detailsCount shouldBe 0
        }

        test("damagedThisTurnFields") {
            val ann = AnnotationBuilder.damagedThisTurn(instanceId = 355)
            ann.typeList shouldContain AnnotationType.DamagedThisTurn
            ann.affectedIdsList shouldContain 355
            ann.detailsCount shouldBe 0
        }

        test("instanceRevealedToOpponentFields") {
            val ann = AnnotationBuilder.instanceRevealedToOpponent(instanceId = 232)
            ann.typeList shouldContain AnnotationType.InstanceRevealedToOpponent
            ann.affectedIdsList shouldContain 232
            ann.detailsCount shouldBe 0
        }

        // --- ColorProduction (Tier 2) ---

        test("colorProductionFields") {
            val ann = AnnotationBuilder.colorProduction(instanceId = 279, colors = 4)
            ann.typeList shouldContain AnnotationType.ColorProduction
            ann.affectedIdsList shouldContain 279
            val colors = ann.detailsList.first { it.key == "colors" }
            colors.type shouldBe KeyValuePairValueType.Int32
            colors.getValueInt32(0) shouldBe 4
        }

        // --- TriggeringObject (Tier 2) ---

        test("triggeringObjectFields") {
            val ann = AnnotationBuilder.triggeringObject(instanceId = 294, sourceZone = 27)
            ann.typeList shouldContain AnnotationType.TriggeringObject
            ann.affectedIdsList shouldContain 294
            ann.detailsList.first { it.key == "source_zone" }.getValueInt32(0) shouldBe 27
        }

        // --- TargetSpec (Tier 2) ---

        test("targetSpecFields") {
            val ann = AnnotationBuilder.targetSpec(
                instanceId = 293,
                abilityGrpId = 176387,
                index = 1,
                promptId = 1330,
                promptParameters = 303,
            )
            ann.typeList shouldContain AnnotationType.TargetSpec
            ann.affectedIdsList shouldContain 293
            ann.detailsList.first { it.key == "abilityGrpId" }.getValueInt32(0) shouldBe 176387
            ann.detailsList.first { it.key == "index" }.getValueInt32(0) shouldBe 1
            ann.detailsList.first { it.key == "promptId" }.getValueInt32(0) shouldBe 1330
            ann.detailsList.first { it.key == "promptParameters" }.getValueInt32(0) shouldBe 303
        }

        // --- PowerToughnessModCreated (Tier 2) ---

        test("powerToughnessModCreatedFields") {
            val ann = AnnotationBuilder.powerToughnessModCreated(instanceId = 335, power = 1, toughness = 1)
            ann.typeList shouldContain AnnotationType.PowerToughnessModCreated
            ann.affectedIdsList shouldContain 335
            ann.detailsList.first { it.key == "power" }.getValueInt32(0) shouldBe 1
            ann.detailsList.first { it.key == "toughness" }.getValueInt32(0) shouldBe 1
        }

        // --- DisplayCardUnderCard (Tier 2) ---

        test("displayCardUnderCardFields") {
            val ann = AnnotationBuilder.displayCardUnderCard(instanceId = 304)
            ann.typeList shouldContain AnnotationType.DisplayCardUnderCard
            ann.affectedIdsList shouldContain 304
            ann.detailsList.first { it.key == "Disable" }.getValueInt32(0) shouldBe 0
            ann.detailsList.first { it.key == "TemporaryZoneTransfer" }.getValueInt32(0) shouldBe 1
        }

        // --- PredictedDirectDamage (Tier 2) ---

        test("predictedDirectDamageFields") {
            val ann = AnnotationBuilder.predictedDirectDamage(instanceId = 336, value = 2)
            ann.typeList shouldContain AnnotationType.PredictedDirectDamage
            ann.affectedIdsList shouldContain 336
            ann.detailsList.first { it.key == "value" }.getValueInt32(0) shouldBe 2
        }
    })
