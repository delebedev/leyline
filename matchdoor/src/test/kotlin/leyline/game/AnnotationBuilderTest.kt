package leyline.game

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.conformance.detail
import leyline.conformance.detailInt
import leyline.conformance.detailString
import leyline.conformance.detailUint
import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

@Suppress("LargeClass")
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
            assertSoftly {
                ann.detailInt("zone_src") shouldBe 31
                ann.detailInt("zone_dest") shouldBe 28
                ann.detailString("category") shouldBe "PlayLand"
            }
            ann.affectedIdsList shouldContain 100
        }

        test("castSpellAnnotation") {
            val ann = AnnotationBuilder.zoneTransfer(
                instanceId = 105,
                srcZoneId = 31, // Hand
                destZoneId = 27, // Stack
                category = "CastSpell",
            )
            assertSoftly {
                ann.detailInt("zone_src") shouldBe 31
                ann.detailInt("zone_dest") shouldBe 27
                ann.detailString("category") shouldBe "CastSpell"
            }
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

            assertSoftly {
                ann.detailInt("orig_id") shouldBe 100
                ann.detailInt("new_id") shouldBe 150
            }
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

            assertSoftly {
                ann.detailInt("actionType") shouldBe 3
                ann.detailInt("abilityGrpId") shouldBe 0
            }
        }

        test("userActionTakenCastType") {
            val ann = AnnotationBuilder.userActionTaken(instanceId = 400, seatId = 2, actionType = 1)
            ann.detailInt("actionType") shouldBe 1
            ann.affectorId shouldBe 2
        }

        // --- ResolutionStart ---

        test("resolutionStartFields") {
            val ann = AnnotationBuilder.resolutionStart(instanceId = 500, grpId = 12345)
            ann.typeList shouldContain AnnotationType.ResolutionStart
            ann.affectorId shouldBe 500
            ann.affectedIdsList shouldContain 500

            ann.detailUint("grpid") shouldBe 12345
        }

        // --- ResolutionComplete ---

        test("resolutionCompleteFields") {
            val ann = AnnotationBuilder.resolutionComplete(instanceId = 500, grpId = 12345)
            ann.typeList shouldContain AnnotationType.ResolutionComplete
            ann.affectorId shouldBe 500
            ann.affectedIdsList shouldContain 500

            ann.detailUint("grpid") shouldBe 12345
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
            val ann = AnnotationBuilder.manaPaid(spellInstanceId = 600, landInstanceId = 42, manaId = 5, color = 4)
            ann.typeList shouldContain AnnotationType.ManaPaid
            ann.affectedIdsList shouldContain 600
            ann.affectorId shouldBe 42

            assertSoftly {
                ann.detailInt("id") shouldBe 5
                ann.detailInt("color") shouldBe 4
            }
        }

        test("manaPaidDefaults") {
            val ann = AnnotationBuilder.manaPaid(spellInstanceId = 600, landInstanceId = 0)
            assertSoftly {
                // Defaults: manaId=0, color=0
                ann.detailInt("id") shouldBe 0
                ann.detailInt("color") shouldBe 0
            }
        }

        // --- TappedUntappedPermanent ---

        test("tappedUntappedPermanentFields") {
            val ann = AnnotationBuilder.tappedUntappedPermanent(permanentId = 700, abilityId = 800)
            ann.typeList shouldContain AnnotationType.TappedUntappedPermanent
            ann.affectorId shouldBe 800
            ann.affectedIdsList shouldContain 700

            ann.detailInt("tapped") shouldBe 1
        }

        test("tappedUntappedPermanentUntapVariant") {
            val ann = AnnotationBuilder.tappedUntappedPermanent(permanentId = 700, abilityId = 800, tapped = false)
            ann.typeList shouldContain AnnotationType.TappedUntappedPermanent
            ann.affectorId shouldBe 800
            ann.affectedIdsList shouldContain 700

            ann.detailInt("tapped") shouldBe 0
        }

        // --- AbilityInstanceCreated ---

        test("abilityInstanceCreatedFields") {
            val ann = AnnotationBuilder.abilityInstanceCreated(abilityInstanceId = 900, sourceZoneId = 31)
            ann.typeList shouldContain AnnotationType.AbilityInstanceCreated
            ann.affectedIdsList shouldContain 900
            ann.affectorId shouldBe 0

            ann.detailInt("source_zone") shouldBe 31
        }

        test("abilityInstanceCreatedWithAffectorId") {
            val ann = AnnotationBuilder.abilityInstanceCreated(abilityInstanceId = 900, affectorId = 42, sourceZoneId = 31)
            ann.affectorId shouldBe 42
            ann.affectedIdsList shouldContain 900
        }

        test("abilityInstanceCreatedDefaultZone") {
            val ann = AnnotationBuilder.abilityInstanceCreated(abilityInstanceId = 900)
            ann.detailInt("source_zone") shouldBe 0
        }

        // --- AbilityInstanceDeleted ---

        test("abilityInstanceDeletedFields") {
            val ann = AnnotationBuilder.abilityInstanceDeleted(abilityInstanceId = 900)
            ann.typeList shouldContain AnnotationType.AbilityInstanceDeleted
            ann.affectedIdsList shouldContain 900
            ann.affectorId shouldBe 0
        }

        test("abilityInstanceDeletedWithAffectorId") {
            val ann = AnnotationBuilder.abilityInstanceDeleted(abilityInstanceId = 900, affectorId = 42)
            ann.affectorId shouldBe 42
            ann.affectedIdsList shouldContain 900
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
            val ann = AnnotationBuilder.damageDealt(
                sourceInstanceId = 1000,
                targetId = 2, // player seat
                amount = 3,
            )
            ann.typeList shouldContain AnnotationType.DamageDealt_af5a
            ann.affectorId shouldBe 1000
            ann.affectedIdsList shouldBe listOf(2)

            assertSoftly {
                ann.detailUint("damage") shouldBe 3
                ann.detailUint("type") shouldBe 1
                ann.detailUint("markDamage") shouldBe 1
            }
        }

        test("damageDealtNonCombat") {
            val ann = AnnotationBuilder.damageDealt(
                sourceInstanceId = 1000,
                targetId = 500, // creature
                amount = 2,
                type = 0,
                markDamage = 2,
            )
            ann.affectorId shouldBe 1000
            ann.affectedIdsList shouldBe listOf(500)
            ann.detailUint("type") shouldBe 0
        }

        // --- ModifiedLife ---

        test("modifiedLifePositiveDelta") {
            val ann = AnnotationBuilder.modifiedLife(playerSeatId = 1, lifeDelta = 3)
            ann.typeList shouldContain AnnotationType.ModifiedLife
            ann.affectedIdsList shouldContain 1

            ann.detailInt("life") shouldBe 3
        }

        test("modifiedLifeNegativeDelta") {
            val ann = AnnotationBuilder.modifiedLife(playerSeatId = 2, lifeDelta = -5)
            ann.detailInt("life") shouldBe -5
        }

        // --- SyntheticEvent ---

        test("syntheticEventFields") {
            val ann = AnnotationBuilder.syntheticEvent(attackerIid = 290, targetSeatId = 2)
            ann.typeList shouldContain AnnotationType.SyntheticEvent
            ann.affectorId shouldBe 290
            ann.affectedIdsList shouldBe listOf(2)
            ann.detailUint("type") shouldBe 1
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

        // --- TemporaryPermanent (persistent) ---

        test("temporaryPermanentFields") {
            val ann = AnnotationBuilder.temporaryPermanent(tokenInstanceId = 371)
            ann.typeList shouldContain AnnotationType.TemporaryPermanent
            ann.affectorId shouldBe 371
            ann.affectedIdsList shouldContain 371
            ann.detailInt(DetailKeys.ABILITY_GRP_ID_UPPER) shouldBe 192424
        }

        // --- CounterAdded (Group B) ---

        test("counterAddedFields") {
            val ann = AnnotationBuilder.counterAdded(instanceId = 100, counterType = "P1P1", amount = 2)
            ann.typeList shouldContain AnnotationType.CounterAdded
            ann.affectedIdsList shouldContain 100

            assertSoftly {
                ann.detailString("counter_type") shouldBe "P1P1"
                ann.detailInt("transaction_amount") shouldBe 2
            }
        }

        // --- CounterRemoved (Group B) ---

        test("counterRemovedFields") {
            val ann = AnnotationBuilder.counterRemoved(instanceId = 200, counterType = "LOYALTY", amount = 3)
            ann.typeList shouldContain AnnotationType.CounterRemoved
            ann.affectedIdsList shouldContain 200

            assertSoftly {
                ann.detailString("counter_type") shouldBe "LOYALTY"
                ann.detailInt("transaction_amount") shouldBe 3
            }
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
            ann.affectorId shouldBe 1500
            ann.affectedIdsList shouldBe listOf(1600)
        }

        // --- Attachment (Group A+ persistent) ---

        test("attachmentFields") {
            val ann = AnnotationBuilder.attachment(auraIid = 1500, targetIid = 1600)
            ann.typeList shouldContain AnnotationType.Attachment
            ann.affectorId shouldBe 1500
            ann.affectedIdsList shouldBe listOf(1600)
        }

        // --- Scry (Group B) ---

        test("scryFields") {
            val ann = AnnotationBuilder.scry(seatId = 1, topCount = 2, bottomCount = 1)
            ann.typeList shouldContain AnnotationType.Scry_af5a
            ann.affectedIdsList shouldContain 1

            assertSoftly {
                ann.detailInt("topCount") shouldBe 2
                ann.detailInt("bottomCount") shouldBe 1
            }
        }

        // --- Counter State (Tier 1) ---

        test("counterStateFields") {
            val ann = AnnotationBuilder.counter(instanceId = 100, counterType = 1, count = 1)
            ann.typeList shouldContain AnnotationType.Counter_803b
            ann.affectedIdsList shouldContain 100
            assertSoftly {
                ann.detailInt("count") shouldBe 1
                ann.detailInt("counter_type") shouldBe 1
            }
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
            assertSoftly {
                ann.detailInt("grpid") shouldBe 6
                ann.detailInt("effect_id") shouldBe 7005
                ann.detailInt("UniqueAbilityId") shouldBe 217
                ann.detailInt("originalAbilityObjectZcid") shouldBe 372
            }
        }

        // --- RemoveAbility (Tier 1) ---

        test("removeAbilityFields") {
            val ann = AnnotationBuilder.removeAbility(instanceId = 200, effectId = 7003)
            ann.typeList shouldContain AnnotationType.RemoveAbility
            ann.affectedIdsList shouldContain 200
            ann.detailInt("effect_id") shouldBe 7003
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
            assertSoftly {
                ann.detailInt("AbilityGrpId") shouldBe 137955
                ann.detailInt("UsesRemaining") shouldBe 0
                ann.detailInt("UniqueAbilityId") shouldBe 205
            }
        }

        // --- GainDesignation (Tier 1) ---

        test("gainDesignationFields") {
            val ann = AnnotationBuilder.gainDesignation(seatId = 1, designationType = 19)
            ann.typeList shouldContain AnnotationType.GainDesignation
            ann.affectedIdsList shouldContain 1
            ann.detailInt("DesignationType") shouldBe 19
        }

        // --- Designation (Tier 1 stub) ---

        test("designationFields") {
            val ann = AnnotationBuilder.designation(seatId = 1, designationType = 19)
            ann.typeList shouldContain AnnotationType.Designation
            ann.affectedIdsList shouldContain 1
            ann.detailInt("DesignationType") shouldBe 19
        }

        // --- LayeredEffect (Tier 1 stub) ---

        test("layeredEffectFields") {
            val ann = AnnotationBuilder.layeredEffect(instanceId = 289, effectId = 7004)
            ann.typeList shouldContain AnnotationType.LayeredEffect
            ann.affectedIdsList shouldContain 289
            ann.detailInt("effect_id") shouldBe 7004
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
            ann.detailInt("effect_id") shouldBe 7005
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
            ann.detailInt("sourceAbilityGRPID") shouldBe 137
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
            assertSoftly {
                ann.detailInt("power") shouldBe 1
                ann.detailInt("toughness") shouldBe 1
            }
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
            val ann = AnnotationBuilder.colorProduction(instanceId = 279, colors = listOf(4))
            ann.typeList shouldContain AnnotationType.ColorProduction
            ann.affectorId shouldBe 279
            ann.affectedIdsList shouldContain 279
            ann.detailInt("colors") shouldBe 4
        }

        test("colorProductionMultiColor") {
            val ann = AnnotationBuilder.colorProduction(instanceId = 283, colors = listOf(4, 5))
            ann.affectorId shouldBe 283
            ann.affectedIdsList shouldContain 283
            val colors = ann.detailsList.first { it.key == "colors" }
            colors.valueInt32Count shouldBe 2
            colors.getValueInt32(0) shouldBe 4
            colors.getValueInt32(1) shouldBe 5
        }

        // --- Color ordinal conversion (used by GameEventCollector.computeColorOrdinals) ---

        test("manaColorMappingProducesArenaOrdinals") {
            assertSoftly {
                ManaColorMapping.fromProduced("W")?.number shouldBe 1
                ManaColorMapping.fromProduced("U")?.number shouldBe 2
                ManaColorMapping.fromProduced("B")?.number shouldBe 3
                ManaColorMapping.fromProduced("R")?.number shouldBe 4
                ManaColorMapping.fromProduced("G")?.number shouldBe 5
            }
        }

        test("manaColorMappingDualLandOrdinals") {
            val wg = listOf("W", "G").mapNotNull { ManaColorMapping.fromProduced(it)?.number }
            wg shouldBe listOf(1, 5)
            val ub = listOf("U", "B").mapNotNull { ManaColorMapping.fromProduced(it)?.number }
            ub shouldBe listOf(2, 3)
        }

        // --- TriggeringObject (Tier 2) ---

        test("triggeringObjectFields") {
            val ann = AnnotationBuilder.triggeringObject(instanceId = 294, sourceZone = 27)
            ann.typeList shouldContain AnnotationType.TriggeringObject
            ann.affectedIdsList shouldContain 294
            ann.detailInt("source_zone") shouldBe 27
        }

        // --- TargetSpec (Tier 2) ---

        test("targetSpecFields") {
            val ann = AnnotationBuilder.targetSpec(
                instanceId = 293,
                affectorId = 303,
                abilityGrpId = 176387,
                index = 1,
                promptId = 1330,
                promptParameters = 303,
            )
            ann.typeList shouldContain AnnotationType.TargetSpec
            ann.affectedIdsList shouldContain 293
            assertSoftly {
                ann.detailInt("abilityGrpId") shouldBe 176387
                ann.detailInt("index") shouldBe 1
                ann.detailInt("promptId") shouldBe 1330
                ann.detailInt("promptParameters") shouldBe 303
            }
        }

        // --- PowerToughnessModCreated (Tier 2) ---

        test("powerToughnessModCreatedFields") {
            val ann = AnnotationBuilder.powerToughnessModCreated(instanceId = 335, power = 1, toughness = 1)
            ann.typeList shouldContain AnnotationType.PowerToughnessModCreated
            ann.affectedIdsList shouldContain 335
            assertSoftly {
                ann.detailInt("power") shouldBe 1
                ann.detailInt("toughness") shouldBe 1
            }
        }

        // --- DisplayCardUnderCard (Tier 2) ---

        test("displayCardUnderCardFields") {
            val ann = AnnotationBuilder.displayCardUnderCard(affectorId = 200, instanceId = 304)
            ann.typeList shouldContain AnnotationType.DisplayCardUnderCard
            ann.affectorId shouldBe 200
            ann.affectedIdsList shouldContain 304
            assertSoftly {
                ann.detailInt("Disable") shouldBe 0
                ann.detailInt("TemporaryZoneTransfer") shouldBe 1
            }
        }

        // --- PredictedDirectDamage (Tier 2) ---

        test("predictedDirectDamageFields") {
            val ann = AnnotationBuilder.predictedDirectDamage(instanceId = 336, value = 2)
            ann.typeList shouldContain AnnotationType.PredictedDirectDamage
            ann.affectedIdsList shouldContain 336
            ann.detailInt("value") shouldBe 2
        }

        // --- Qualification (Tier 1 persistent) ---

        test("qualificationAdventure") {
            val ann = AnnotationBuilder.qualification(instanceId = 348)
            ann.typeList shouldContain AnnotationType.Qualification
            assertSoftly {
                ann.affectedIdsList shouldBe listOf(348)
                ann.detailUint("QualificationType") shouldBe 47
                ann.detailUint("QualificationSubtype") shouldBe 0
                ann.detailUint("grpid") shouldBe 196
                ann.detailUint("SourceParent") shouldBe 0
            }
            ann.affectorId shouldBe 0
        }

        // --- AbilityWordActive (Tier 1 persistent) ---

        test("abilityWordActiveQuantitative") {
            val ann = AnnotationBuilder.abilityWordActive(
                instanceId = 295,
                abilityWordName = "Threshold",
                value = 5,
                threshold = 7,
                abilityGrpId = 175886,
            )
            ann.typeList shouldContain AnnotationType.AbilityWordActive
            assertSoftly {
                ann.affectorId shouldBe 295
                ann.affectedIdsList shouldBe listOf(295)
                ann.detailString("AbilityWordName") shouldBe "Threshold"
                ann.detailInt("value") shouldBe 5
                ann.detailInt("threshold") shouldBe 7
                ann.detailInt("AbilityGrpId") shouldBe 175886
            }
        }

        test("abilityWordActiveKeywordOnly") {
            val ann = AnnotationBuilder.abilityWordActive(
                instanceId = 303,
                abilityWordName = "Descended",
                affectorId = 1,
            )
            ann.typeList shouldContain AnnotationType.AbilityWordActive
            assertSoftly {
                ann.affectorId shouldBe 1
                ann.affectedIdsList shouldBe listOf(303)
                ann.detailString("AbilityWordName") shouldBe "Descended"
            }
            // No value/threshold/abilityGrpId details for keyword-only variants
            ann.detail("value") shouldBe null
            ann.detail("threshold") shouldBe null
        }

        test("qualification annotation shape") {
            val ann = AnnotationBuilder.qualification(
                affectorId = 287,
                instanceId = 287,
                grpId = 142,
                qualificationType = 40,
                qualificationSubtype = 0,
                sourceParent = 287,
            )
            ann.typeList shouldContain AnnotationType.Qualification
            ann.affectorId shouldBe 287
            ann.affectedIdsList shouldContain 287
            assertSoftly {
                ann.detailUint("grpid") shouldBe 142
                ann.detailUint("QualificationType") shouldBe 40
                ann.detailUint("QualificationSubtype") shouldBe 0
                ann.detailUint("SourceParent") shouldBe 287
            }
        }
    })
