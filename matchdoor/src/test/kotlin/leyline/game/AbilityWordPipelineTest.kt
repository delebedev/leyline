package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
import leyline.conformance.detailInt
import leyline.conformance.detailString
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class AbilityWordPipelineTest :
    FunSpec({

        tags(UnitTag)

        fun testResolver(forgeCardId: ForgeCardId): InstanceId = InstanceId(forgeCardId.value + 1000)

        fun mechanicResult(
            abilityWordPersistent: List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo> = emptyList(),
        ) =
            AnnotationPipeline.MechanicAnnotationResult(
                transient = emptyList(),
                persistent = emptyList(),
                abilityWordPersistent = abilityWordPersistent,
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
    })
