package leyline.game

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
import leyline.conformance.detail
import leyline.conformance.detailInt
import leyline.conformance.detailString
import leyline.conformance.detailUint
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class AbilityWordPipelineTest :
    FunSpec({

        tags(UnitTag)

        fun testResolver(forgeCardId: ForgeCardId): InstanceId = InstanceId(forgeCardId.value + 1000)

        fun mechanicResult(
            abilityWordPersistent: List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo> = emptyList(),
            qualificationPersistent: List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo> = emptyList(),
        ) =
            MechanicAnnotationResult(
                transient = emptyList(),
                persistent = emptyList(),
                abilityWordPersistent = abilityWordPersistent,
                qualificationPersistent = qualificationPersistent,
            )

        test("AbilityWordActive created in first batch") {
            val ann = AnnotationBuilder.abilityWordActive(
                instanceId = 295,
                abilityWordName = "Threshold",
                value = 5,
                threshold = 7,
                abilityGrpId = 175886,
            )

            val result = PersistentAnnotationStore.computeBatch(
                currentActive = emptyMap(),
                startPersistentId = 1,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = mechanicResult(abilityWordPersistent = listOf(ann)),
                resolveInstanceId = ::testResolver,
            )

            val awAnns = result.allAnnotations.filter {
                AnnotationType.AbilityWordActive in it.typeList
            }
            awAnns shouldHaveSize 1
            awAnns[0].detailString("AbilityWordName") shouldBe "Threshold"
            awAnns[0].detailInt("value") shouldBe 5
            result.deletedIds.shouldBeEmpty()
        }

        test("AbilityWordActive upsert replaces on value change") {
            val old = AnnotationBuilder.abilityWordActive(
                instanceId = 295,
                abilityWordName = "Threshold",
                value = 5,
                threshold = 7,
                abilityGrpId = 175886,
            ).toBuilder().setId(3).build()

            val updated = AnnotationBuilder.abilityWordActive(
                instanceId = 295,
                abilityWordName = "Threshold",
                value = 7,
                threshold = 7,
                abilityGrpId = 175886,
            )

            val result = PersistentAnnotationStore.computeBatch(
                currentActive = mapOf(3 to old),
                startPersistentId = 10,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = mechanicResult(abilityWordPersistent = listOf(updated)),
                resolveInstanceId = ::testResolver,
            )

            val awAnns = result.allAnnotations.filter {
                AnnotationType.AbilityWordActive in it.typeList
            }
            awAnns shouldHaveSize 1
            awAnns[0].detailInt("value") shouldBe 7
            awAnns[0].id shouldBe 10
            result.deletedIds shouldBe listOf(3)
        }

        test("AbilityWordActive removed when absent from new scan") {
            val old = AnnotationBuilder.abilityWordActive(
                instanceId = 295,
                abilityWordName = "Threshold",
                value = 5,
                threshold = 7,
                abilityGrpId = 175886,
            ).toBuilder().setId(3).build()

            val result = PersistentAnnotationStore.computeBatch(
                currentActive = mapOf(3 to old),
                startPersistentId = 10,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = mechanicResult(abilityWordPersistent = emptyList()),
                resolveInstanceId = ::testResolver,
            )

            result.allAnnotations.filter {
                AnnotationType.AbilityWordActive in it.typeList
            }.shouldBeEmpty()
            result.deletedIds shouldBe listOf(3)
        }

        test("AbilityWordActive unchanged value is not churned") {
            val existing = AnnotationBuilder.abilityWordActive(
                instanceId = 295,
                abilityWordName = "Threshold",
                value = 5,
                threshold = 7,
                abilityGrpId = 175886,
            ).toBuilder().setId(3).build()

            val same = AnnotationBuilder.abilityWordActive(
                instanceId = 295,
                abilityWordName = "Threshold",
                value = 5,
                threshold = 7,
                abilityGrpId = 175886,
            )

            val result = PersistentAnnotationStore.computeBatch(
                currentActive = mapOf(3 to existing),
                startPersistentId = 10,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = mechanicResult(abilityWordPersistent = listOf(same)),
                resolveInstanceId = ::testResolver,
            )

            val awAnns = result.allAnnotations.filter {
                AnnotationType.AbilityWordActive in it.typeList
            }
            awAnns shouldHaveSize 1
            awAnns[0].id shouldBe 3
            result.deletedIds.shouldBeEmpty()
        }

        test("Morbid boolean-only pAnn with seatId affector and multiple affectedIds") {
            val ann = AnnotationBuilder.abilityWordActive(
                instanceId = 1, // seatId as stable key
                abilityWordName = "Morbid",
                affectorId = 1,
                affectedIds = listOf(323, 328),
            )

            val result = PersistentAnnotationStore.computeBatch(
                currentActive = emptyMap(),
                startPersistentId = 5,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = mechanicResult(abilityWordPersistent = listOf(ann)),
                resolveInstanceId = ::testResolver,
            )

            val awAnns = result.allAnnotations.filter {
                AnnotationType.AbilityWordActive in it.typeList
            }
            awAnns shouldHaveSize 1
            assertSoftly {
                awAnns[0].affectorId shouldBe 1
                awAnns[0].affectedIdsList shouldBe listOf(323, 328)
                awAnns[0].detailString("AbilityWordName") shouldBe "Morbid"
                awAnns[0].detail("value").shouldBeNull()
                awAnns[0].detail("threshold").shouldBeNull()
            }
            result.deletedIds.shouldBeEmpty()
        }

        // --- Qualification (adventure exile) ---

        test("Qualification created in first batch") {
            val ann = AnnotationBuilder.qualification(instanceId = 348)

            val result = PersistentAnnotationStore.computeBatch(
                currentActive = emptyMap(),
                startPersistentId = 1,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = mechanicResult(qualificationPersistent = listOf(ann)),
                resolveInstanceId = ::testResolver,
            )

            val qAnns = result.allAnnotations.filter {
                AnnotationType.Qualification in it.typeList
            }
            qAnns shouldHaveSize 1
            qAnns[0].affectedIdsList shouldBe listOf(348)
            qAnns[0].detailUint("QualificationType") shouldBe 47
            result.deletedIds.shouldBeEmpty()
        }

        test("Qualification removed when card leaves exile") {
            val old = AnnotationBuilder.qualification(instanceId = 348)
                .toBuilder().setId(5).build()

            val result = PersistentAnnotationStore.computeBatch(
                currentActive = mapOf(5 to old),
                startPersistentId = 10,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = mechanicResult(qualificationPersistent = emptyList()),
                resolveInstanceId = ::testResolver,
            )

            result.allAnnotations.filter {
                AnnotationType.Qualification in it.typeList
            }.shouldBeEmpty()
            result.deletedIds shouldBe listOf(5)
        }

        test("Qualification not churned when unchanged") {
            val existing = AnnotationBuilder.qualification(instanceId = 348)
                .toBuilder().setId(5).build()

            val same = AnnotationBuilder.qualification(instanceId = 348)

            val result = PersistentAnnotationStore.computeBatch(
                currentActive = mapOf(5 to existing),
                startPersistentId = 10,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = mechanicResult(qualificationPersistent = listOf(same)),
                resolveInstanceId = ::testResolver,
            )

            val qAnns = result.allAnnotations.filter {
                AnnotationType.Qualification in it.typeList
            }
            qAnns shouldHaveSize 1
            qAnns[0].id shouldBe 5
            result.deletedIds.shouldBeEmpty()
        }
    })
