package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.MechanicAnnotationResult
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

class LinkInfoChoicePersistentTest :
    FunSpec({
        tags(UnitTag)

        val emptyEffectDiff = EffectTracker.DiffResult(emptyList(), emptyList())

        fun mechanicResult(vararg annotations: AnnotationInfo): MechanicAnnotationResult =
            MechanicAnnotationResult(
                transient = emptyList(),
                persistent = emptyList(),
                perKindPersistent = mapOf(LinkInfoChoiceKind to annotations.toList()),
            )

        fun compute(
            currentActive: Map<Int, AnnotationInfo>,
            startPersistentId: Int,
            mechanicResult: MechanicAnnotationResult,
        ): PersistentAnnotationStore.BatchResult =
            PersistentAnnotationStore.computeBatch(
                currentActive = currentActive,
                startPersistentId = startPersistentId,
                effectPersistent = emptyList(),
                effectDiff = emptyEffectDiff,
                transferPersistent = emptyList(),
                mechanicResult = mechanicResult,
                resolveInstanceId = { InstanceId(it.value) },
            )

        test("LinkInfo choice annotation carries forward while incoming choice stays present") {
            val linkInfo =
                AnnotationBuilder.linkInfoChoice(
                    sourceInstanceId = InstanceId(414),
                    affectedIds = listOf(1),
                    chooseLinkType = "Color",
                    sourceAbilityGrpId = GrpId(88237),
                )
            val first = compute(emptyMap(), startPersistentId = 10, mechanicResult(linkInfo))
            val second = compute(first.allAnnotations.associateBy { it.id }, first.nextPersistentId, mechanicResult(linkInfo))

            assertSoftly {
                first.allAnnotations.single().id shouldBe 10
                second.deletedIds.shouldBeEmpty()
                second.allAnnotations.single().id shouldBe 10
                second.nextPersistentId shouldBe 11
            }
        }

        test("LinkInfo choice annotation is replaced when the chosen value changes") {
            val redChoice =
                AnnotationBuilder.linkInfoChoice(
                    sourceInstanceId = InstanceId(414),
                    affectedIds = listOf(4),
                    chooseLinkType = "Color",
                    sourceAbilityGrpId = GrpId(88237),
                )
            val greenChoice =
                AnnotationBuilder.linkInfoChoice(
                    sourceInstanceId = InstanceId(414),
                    affectedIds = listOf(5),
                    chooseLinkType = "Color",
                    sourceAbilityGrpId = GrpId(88237),
                )
            val first = compute(emptyMap(), startPersistentId = 10, mechanicResult(redChoice))
            val second = compute(first.allAnnotations.associateBy { it.id }, first.nextPersistentId, mechanicResult(greenChoice))

            assertSoftly {
                second.deletedIds shouldContainExactly listOf(10)
                second.allAnnotations.single().id shouldBe 11
                second.allAnnotations.single().affectedIdsList shouldBe listOf(5)
            }
        }

        test("LinkInfo choice annotation is pruned when no choice is incoming") {
            val linkInfo =
                AnnotationBuilder.linkInfoChoice(
                    sourceInstanceId = InstanceId(435),
                    affectedIds = listOf(6, 176),
                    chooseLinkType = "Type",
                    sourceAbilityGrpId = GrpId(176647),
                )
            val first = compute(emptyMap(), startPersistentId = 10, mechanicResult(linkInfo))
            val second =
                compute(
                    currentActive = first.allAnnotations.associateBy { it.id },
                    startPersistentId = first.nextPersistentId,
                    mechanicResult = mechanicResult(),
                )

            assertSoftly {
                second.deletedIds shouldContainExactly listOf(10)
                second.allAnnotations.shouldBeEmpty()
            }
        }
    })
