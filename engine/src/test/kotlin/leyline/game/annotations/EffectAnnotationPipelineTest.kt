package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.annotations.MechanicAnnotations
import leyline.game.state.EffectTracker
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Effect-stage annotation pipeline tests — effectAnnotations, boost tracking,
 * LayeredEffect creation/destruction, and source-ability attribution emission.
 */
class EffectAnnotationPipelineTest :
    FunSpec({

        tags(UnitTag)

        test("effectAnnotations emits Created + persistent LayeredEffect for new boost") {
            val created =
                listOf(
                    EffectTracker.TrackedEffect(
                        syntheticId = 7005,
                        fingerprint = EffectTracker.EffectFingerprint(100, 1L, 0L),
                        powerDelta = 3,
                        toughnessDelta = 3,
                    ),
                )
            val diff = EffectTracker.DiffResult(created, emptyList())

            val (transient, persistent) = MechanicAnnotations.effectAnnotations(diff)

            // Transient: LayeredEffectCreated + PowerToughnessModCreated companion
            assertSoftly {
                transient.size shouldBe 2
                transient[0].typeList.first() shouldBe AnnotationType.LayeredEffectCreated
                transient[0].affectedIdsList shouldBe listOf(7005)
                transient[1].typeList.first() shouldBe AnnotationType.PowerToughnessModCreated
                transient[1].affectedIdsList shouldBe listOf(100)
                transient[1].affectorId shouldBe 100
                transient[1].detailInt("power") shouldBe 3
                transient[1].detailInt("toughness") shouldBe 3
            }

            assertSoftly {
                persistent.size shouldBe 1
                persistent[0].typeList shouldContain AnnotationType.LayeredEffect
                persistent[0].affectedIdsList shouldBe listOf(100)
                persistent[0].detailInt("effect_id") shouldBe 7005
            }
        }

        test("effectAnnotations emits Destroyed for removed boost") {
            val destroyed =
                listOf(
                    EffectTracker.TrackedEffect(
                        syntheticId = 7005,
                        fingerprint = EffectTracker.EffectFingerprint(100, 1L, 0L),
                        powerDelta = 3,
                        toughnessDelta = 3,
                    ),
                )
            val diff = EffectTracker.DiffResult(emptyList(), destroyed)

            val (transient, persistent) = MechanicAnnotations.effectAnnotations(diff)

            assertSoftly {
                transient.size shouldBe 1
                transient[0].typeList.first() shouldBe AnnotationType.LayeredEffectDestroyed
                transient[0].affectedIdsList shouldBe listOf(7005)
                persistent.shouldBeEmpty()
            }
        }

        test("effectAnnotations empty diff produces no annotations") {
            val diff = EffectTracker.DiffResult(emptyList(), emptyList())
            val (transient, persistent) = MechanicAnnotations.effectAnnotations(diff)
            transient.shouldBeEmpty()
            persistent.shouldBeEmpty()
        }

        test("effectAnnotations uses multi-type array based on deltas (no LayeredEffectType)") {
            // Both power and toughness changed → [ModifiedToughness, ModifiedPower, LayeredEffect]
            val both =
                EffectTracker.DiffResult(
                    listOf(EffectTracker.TrackedEffect(7005, EffectTracker.EffectFingerprint(100, 1L, 0L), 3, 3)),
                    emptyList(),
                )
            val (transientBoth, persistentBoth) = MechanicAnnotations.effectAnnotations(both)
            assertSoftly {
                persistentBoth[0].typeList shouldContain AnnotationType.ModifiedPower
                persistentBoth[0].typeList shouldContain AnnotationType.ModifiedToughness
                persistentBoth[0].typeList shouldContain AnnotationType.LayeredEffect
                persistentBoth[0].detailsList.none { it.key == "LayeredEffectType" } shouldBe true
            }
            // Companion PowerToughnessModCreated emitted
            transientBoth.any { it.typeList.contains(AnnotationType.PowerToughnessModCreated) } shouldBe true

            // Only power changed → [ModifiedPower, LayeredEffect], no ModifiedToughness
            val powerOnly =
                EffectTracker.DiffResult(
                    listOf(EffectTracker.TrackedEffect(7006, EffectTracker.EffectFingerprint(101, 2L, 0L), 2, 0)),
                    emptyList(),
                )
            val (_, persistentPower) = MechanicAnnotations.effectAnnotations(powerOnly)
            assertSoftly {
                persistentPower[0].typeList shouldContain AnnotationType.ModifiedPower
                persistentPower[0].typeList shouldContain AnnotationType.LayeredEffect
                persistentPower[0].typeList.none { it == AnnotationType.ModifiedToughness } shouldBe true
            }

            // Only toughness changed → [ModifiedToughness, LayeredEffect], no ModifiedPower
            val toughOnly =
                EffectTracker.DiffResult(
                    listOf(EffectTracker.TrackedEffect(7007, EffectTracker.EffectFingerprint(102, 3L, 0L), 0, 1)),
                    emptyList(),
                )
            val (_, persistentTough) = MechanicAnnotations.effectAnnotations(toughOnly)
            assertSoftly {
                persistentTough[0].typeList shouldContain AnnotationType.ModifiedToughness
                persistentTough[0].typeList shouldContain AnnotationType.LayeredEffect
                persistentTough[0].typeList.none { it == AnnotationType.ModifiedPower } shouldBe true
            }
        }

        test("effectAnnotations emits tracked source ability attribution") {
            val staticId = 42L
            val created =
                listOf(
                    EffectTracker.TrackedEffect(
                        syntheticId = 7010,
                        fingerprint = EffectTracker.EffectFingerprint(100, 1L, staticId),
                        powerDelta = 1,
                        toughnessDelta = 1,
                        sourceAbilityGrpId = 99999,
                    ),
                )
            val diff = EffectTracker.DiffResult(created, emptyList())

            val (_, persistent) = MechanicAnnotations.effectAnnotations(diff)

            persistent.size shouldBe 1
            val sourceDetail = persistent[0].detailsList.first { it.key == "sourceAbilityGRPID" }
            sourceDetail.getValueInt32(0) shouldBe 99999
        }

        test("effectAnnotations omits source ability attribution when absent") {
            val created =
                listOf(
                    EffectTracker.TrackedEffect(
                        syntheticId = 7011,
                        fingerprint = EffectTracker.EffectFingerprint(100, 1L, 0L),
                        powerDelta = 2,
                        toughnessDelta = 0,
                    ),
                )
            val diff = EffectTracker.DiffResult(created, emptyList())

            val (_, persistent) = MechanicAnnotations.effectAnnotations(diff)

            persistent.size shouldBe 1
            persistent[0].detailsList.none { it.key == "sourceAbilityGRPID" } shouldBe true
        }

        test("source-owned boost emits one persistent row over all affected cards") {
            val effect =
                EffectTracker.TrackedEffect(
                    syntheticId = 7012,
                    fingerprint = EffectTracker.EffectFingerprint(0, 1L, 7L, ForgeCardId(900)),
                    powerDelta = 1,
                    toughnessDelta = 0,
                    sourceAbilityGrpId = 170351,
                    sourceForgeCardId = ForgeCardId(900),
                    affectedCardInstanceIds = listOf(101, 102, 103),
                )

            val (transient, persistent) =
                MechanicAnnotations.effectAnnotations(
                    diff = EffectTracker.DiffResult(listOf(effect), emptyList()),
                    boostAffectorResolver = { tracked, _ -> tracked.sourceForgeCardId?.let { InstanceId(100) } },
                )

            val createdAnnotation = transient.single { AnnotationType.LayeredEffectCreated in it.typeList }
            val persistentAnnotation = persistent.single()
            assertSoftly {
                createdAnnotation.affectorId shouldBe 100
                persistentAnnotation.affectedIdsList shouldBe listOf(101, 102, 103)
                persistentAnnotation.affectorId shouldBe 100
                persistentAnnotation.detailsList.none { it.key == "sourceAbilityGRPID" } shouldBe true
            }
        }
    })
