package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.MechanicAnnotationResult
import leyline.game.grp
import leyline.game.iid
import leyline.game.state.AbilityWordActiveKind
import leyline.game.state.EffectTracker
import leyline.game.state.PersistentAnnotationStore
import leyline.game.state.QualificationKind
import leyline.testkit.detail
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.detailUint
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class AbilityWordPipelineTest :
    FunSpec({

        tags(UnitTag)

        fun testResolver(forgeCardId: ForgeCardId): InstanceId = InstanceId(forgeCardId.value + 1000)

        fun mechanicResult(
            abilityWordPersistent: List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo> = emptyList(),
            qualificationPersistent: List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo> = emptyList(),
        ) = MechanicAnnotationResult(
            transient = emptyList(),
            persistent = emptyList(),
            perKindPersistent =
                mapOf(
                    AbilityWordActiveKind to abilityWordPersistent,
                    QualificationKind to qualificationPersistent,
                ),
        )

        test("AbilityWordActive created in first batch") {
            val ann =
                AnnotationBuilder.abilityWordActive(
                    instanceId = 295.iid,
                    abilityWordName = "Threshold",
                    value = 5,
                    threshold = 7,
                    abilityGrpId = 175886.grp,
                )

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = emptyMap(),
                    startPersistentId = 1,
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult = mechanicResult(abilityWordPersistent = listOf(ann)),
                    resolveInstanceId = ::testResolver,
                )

            val awAnns =
                result.allAnnotations.filter {
                    AnnotationType.AbilityWordActive in it.typeList
                }
            assertSoftly {
                awAnns shouldHaveSize 1
                awAnns[0].detailString("AbilityWordName") shouldBe "Threshold"
                awAnns[0].detailInt("value") shouldBe 5
                result.deletedIds.shouldBeEmpty()
            }
        }

        test("AbilityWordActive upsert replaces on value change") {
            val old =
                AnnotationBuilder
                    .abilityWordActive(
                        instanceId = 295.iid,
                        abilityWordName = "Threshold",
                        value = 5,
                        threshold = 7,
                        abilityGrpId = 175886.grp,
                    ).toBuilder()
                    .setId(3)
                    .build()

            val updated =
                AnnotationBuilder.abilityWordActive(
                    instanceId = 295.iid,
                    abilityWordName = "Threshold",
                    value = 7,
                    threshold = 7,
                    abilityGrpId = 175886.grp,
                )

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = mapOf(3 to old),
                    startPersistentId = 10,
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult = mechanicResult(abilityWordPersistent = listOf(updated)),
                    resolveInstanceId = ::testResolver,
                )

            val awAnns =
                result.allAnnotations.filter {
                    AnnotationType.AbilityWordActive in it.typeList
                }
            assertSoftly {
                awAnns shouldHaveSize 1
                awAnns[0].detailInt("value") shouldBe 7
                awAnns[0].id shouldBe 10
                result.deletedIds shouldBe listOf(3)
            }
        }

        test("multi-threshold ability words keep distinct rows across a value update") {
            fun expendRows(value: Int) =
                listOf(
                    AnnotationBuilder.abilityWordActive(
                        instanceId = 295.iid,
                        abilityWordName = "ExpendedMana",
                        value = value,
                        threshold = 4,
                        abilityGrpId = 174143.grp,
                    ),
                    AnnotationBuilder.abilityWordActive(
                        instanceId = 295.iid,
                        abilityWordName = "ExpendedMana",
                        value = value,
                        threshold = 8,
                        abilityGrpId = 174144.grp,
                    ),
                )

            val initial =
                PersistentAnnotationStore.computeBatch(
                    currentActive = emptyMap(),
                    startPersistentId = 5,
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult = mechanicResult(abilityWordPersistent = expendRows(value = 0)),
                    resolveInstanceId = ::testResolver,
                )
            val updated =
                PersistentAnnotationStore.computeBatch(
                    currentActive = initial.allAnnotations.associateBy { it.id },
                    startPersistentId = initial.nextPersistentId,
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult = mechanicResult(abilityWordPersistent = expendRows(value = 4)),
                    resolveInstanceId = ::testResolver,
                )
            val rows =
                updated.allAnnotations.filter {
                    AnnotationType.AbilityWordActive in it.typeList &&
                        it.detailString("AbilityWordName") == "ExpendedMana"
                }

            assertSoftly {
                rows shouldHaveSize 2
                rows.map { it.detailInt("threshold") }.toSet() shouldBe setOf(4, 8)
                rows.map { it.detailInt("AbilityGrpId") }.toSet() shouldBe setOf(174143, 174144)
                rows.map { it.detailInt("value") }.toSet() shouldBe setOf(4)
                updated.deletedIds.toSet() shouldBe initial.allAnnotations.map { it.id }.toSet()
            }
        }

        test("AbilityWordActive removed when absent from new scan") {
            val old =
                AnnotationBuilder
                    .abilityWordActive(
                        instanceId = 295.iid,
                        abilityWordName = "Threshold",
                        value = 5,
                        threshold = 7,
                        abilityGrpId = 175886.grp,
                    ).toBuilder()
                    .setId(3)
                    .build()

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = mapOf(3 to old),
                    startPersistentId = 10,
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult = mechanicResult(abilityWordPersistent = emptyList()),
                    resolveInstanceId = ::testResolver,
                )

            result.allAnnotations
                .filter {
                    AnnotationType.AbilityWordActive in it.typeList
                }.shouldBeEmpty()
            result.deletedIds shouldBe listOf(3)
        }

        test("AbilityWordActive unchanged value is not churned") {
            val existing =
                AnnotationBuilder
                    .abilityWordActive(
                        instanceId = 295.iid,
                        abilityWordName = "Threshold",
                        value = 5,
                        threshold = 7,
                        abilityGrpId = 175886.grp,
                    ).toBuilder()
                    .setId(3)
                    .build()

            val same =
                AnnotationBuilder.abilityWordActive(
                    instanceId = 295.iid,
                    abilityWordName = "Threshold",
                    value = 5,
                    threshold = 7,
                    abilityGrpId = 175886.grp,
                )

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = mapOf(3 to existing),
                    startPersistentId = 10,
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult = mechanicResult(abilityWordPersistent = listOf(same)),
                    resolveInstanceId = ::testResolver,
                )

            val awAnns =
                result.allAnnotations.filter {
                    AnnotationType.AbilityWordActive in it.typeList
                }
            assertSoftly {
                awAnns shouldHaveSize 1
                awAnns[0].id shouldBe 3
                result.deletedIds.shouldBeEmpty()
            }
        }

        test("Morbid boolean-only pAnn with seatId affector and multiple affectedIds") {
            val ann =
                AnnotationBuilder.abilityWordActive(
                    instanceId = 1.iid, // seatId as stable key
                    abilityWordName = "Morbid",
                    affectorId = 1.iid,
                    affectedIds = listOf(323.iid, 328.iid),
                )

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = emptyMap(),
                    startPersistentId = 5,
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult = mechanicResult(abilityWordPersistent = listOf(ann)),
                    resolveInstanceId = ::testResolver,
                )

            val awAnns =
                result.allAnnotations.filter {
                    AnnotationType.AbilityWordActive in it.typeList
                }
            assertSoftly {
                awAnns shouldHaveSize 1
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
            val ann = AnnotationBuilder.qualification(instanceId = 348.iid)

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = emptyMap(),
                    startPersistentId = 1,
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult = mechanicResult(qualificationPersistent = listOf(ann)),
                    resolveInstanceId = ::testResolver,
                )

            val qAnns =
                result.allAnnotations.filter {
                    AnnotationType.Qualification in it.typeList
                }
            assertSoftly {
                qAnns shouldHaveSize 1
                qAnns[0].affectedIdsList shouldBe listOf(348)
                qAnns[0].detailUint("QualificationType") shouldBe 47
                result.deletedIds.shouldBeEmpty()
            }
        }

        test("Qualification removed when card leaves exile") {
            val old =
                AnnotationBuilder
                    .qualification(instanceId = 348.iid)
                    .toBuilder()
                    .setId(5)
                    .build()

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = mapOf(5 to old),
                    startPersistentId = 10,
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult = mechanicResult(qualificationPersistent = emptyList()),
                    resolveInstanceId = ::testResolver,
                )

            result.allAnnotations
                .filter {
                    AnnotationType.Qualification in it.typeList
                }.shouldBeEmpty()
            result.deletedIds shouldBe listOf(5)
        }

        test("Qualification not churned when unchanged") {
            val existing =
                AnnotationBuilder
                    .qualification(instanceId = 348.iid)
                    .toBuilder()
                    .setId(5)
                    .build()

            val same = AnnotationBuilder.qualification(instanceId = 348.iid)

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = mapOf(5 to existing),
                    startPersistentId = 10,
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult = mechanicResult(qualificationPersistent = listOf(same)),
                    resolveInstanceId = ::testResolver,
                )

            val qAnns =
                result.allAnnotations.filter {
                    AnnotationType.Qualification in it.typeList
                }
            assertSoftly {
                qAnns shouldHaveSize 1
                qAnns[0].id shouldBe 5
                result.deletedIds.shouldBeEmpty()
            }
        }
    })
