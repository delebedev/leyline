package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.MechanicAnnotationResult
import leyline.game.annotations.MechanicAnnotations
import leyline.game.codes.DetailKeys
import leyline.game.state.PersistentAnnotationStore
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class PlayerSpeedProjectionTest :
    FunSpec({
        tags(UnitTag)

        test("speed designation updates in place and carries the player holder") {
            val first = AnnotationBuilder.playerSpeedDesignation(SeatId(1), 3, InstanceId(100_000_001))
            val firstBatch =
                PersistentAnnotationStore.computeBatch(
                    currentActive = emptyMap(),
                    startPersistentId = 1,
                    frame = FrameContext(null, SeatId(1), emptySet(), emptyMap()),
                    effectPersistent = emptyList(),
                    effectDiff = emptyEffectDiff(),
                    transferPersistent = emptyList(),
                    mechanicResult = speedResult(first),
                    resolveInstanceId = { InstanceId(it.value) },
                )
            val original = firstBatch.allAnnotations.single()
            val changed = AnnotationBuilder.playerSpeedDesignation(SeatId(1), 4, InstanceId(100_000_001))
            val secondBatch =
                PersistentAnnotationStore.computeBatch(
                    currentActive = mapOf(original.id to original),
                    startPersistentId = firstBatch.nextPersistentId,
                    frame = FrameContext(null, SeatId(1), emptySet(), emptyMap()),
                    effectPersistent = emptyList(),
                    effectDiff = emptyEffectDiff(),
                    transferPersistent = emptyList(),
                    mechanicResult = speedResult(changed),
                    resolveInstanceId = { InstanceId(it.value) },
                )
            val updated = secondBatch.allAnnotations.single()
            assertSoftly {
                original.typeList shouldBe listOf(AnnotationType.Designation)
                original.affectorId shouldBe 1
                original.affectedIdsList shouldBe listOf(1, 100_000_001)
                detailInt(original, DetailKeys.DESIGNATION_TYPE) shouldBe 21
                detailInt(original, DetailKeys.VALUE) shouldBe 3
                updated.id shouldBe original.id
                detailInt(updated, DetailKeys.VALUE) shouldBe 4
                secondBatch.deletedIds.shouldBeEmpty()
            }
        }

        test("granted activated ability emits numeric AddAbility lifecycle and retires") {
            val tracker = EffectTracker()
            val current =
                mapOf(
                    77 to
                        listOf(
                            EffectTracker.GrantedAbilityEntry(
                                timestamp = 3L,
                                staticId = 44L,
                                abilityGrpId = 179264,
                                uniqueAbilityId = 53,
                                sourceForgeCardId = ForgeCardId(9001),
                            ),
                        ),
                )
            val created = tracker.diffGrantedAbilities(current)
            val (createdTransient, createdPersistent) =
                MechanicAnnotations.effectAnnotations(
                    diff = emptyEffectDiff(),
                    grantedAbilityDiff = created,
                    grantedAbilitySourceInstanceId = { source ->
                        if (source == ForgeCardId(9001)) InstanceId(88) else error("unexpected source: $source")
                    },
                )
            val pAnn = createdPersistent.single()
            val speed = AnnotationBuilder.playerSpeedDesignation(SeatId(1), 4, InstanceId(100_000_001))
            assertSoftly {
                createdTransient.single().typeList shouldBe listOf(AnnotationType.LayeredEffectCreated)
                pAnn.typeList shouldBe listOf(AnnotationType.AddAbility_af5a, AnnotationType.LayeredEffect)
                pAnn.affectedIdsList shouldBe listOf(77)
                pAnn.affectorId shouldBe 88
                detailInt(pAnn, DetailKeys.GRPID) shouldBe 179264
                detailInt(pAnn, DetailKeys.UNIQUE_ABILITY_ID) shouldBe 53
                detailInt(pAnn, DetailKeys.ORIGINAL_ABILITY_OBJECT_ZCID) shouldBe 88
                detailInt(pAnn, DetailKeys.EFFECT_ID) shouldBe created.created.single().syntheticId
            }

            val numbered =
                PersistentAnnotationStore.computeBatch(
                    currentActive = emptyMap(),
                    startPersistentId = 1,
                    frame = FrameContext.INERT,
                    effectPersistent = listOf(pAnn),
                    effectDiff = emptyEffectDiff(),
                    transferPersistent = emptyList(),
                    mechanicResult = speedResult(speed),
                    resolveInstanceId = { InstanceId(it.value) },
                )
            val numberedEffect = numbered.allAnnotations.single { AnnotationType.LayeredEffect in it.typeList }
            val retired =
                PersistentAnnotationStore.computeBatch(
                    currentActive = numbered.allAnnotations.associateBy { it.id },
                    startPersistentId = numbered.nextPersistentId,
                    frame = FrameContext.INERT,
                    effectPersistent = emptyList(),
                    effectDiff =
                        EffectTracker.DiffResult(
                            created = emptyList(),
                            destroyed =
                                listOf(
                                    EffectTracker.TrackedEffect(
                                        syntheticId = created.created.single().syntheticId,
                                        fingerprint = EffectTracker.EffectFingerprint(77, 3L, 44L),
                                        powerDelta = 0,
                                        toughnessDelta = 0,
                                    ),
                                ),
                        ),
                    transferPersistent = emptyList(),
                    mechanicResult = speedResult(speed),
                    resolveInstanceId = { InstanceId(it.value) },
                )

            val destroyed = tracker.diffGrantedAbilities(emptyMap())
            val (destroyedTransient, destroyedPersistent) =
                MechanicAnnotations.effectAnnotations(
                    diff = emptyEffectDiff(),
                    grantedAbilityDiff = destroyed,
                    grantedAbilitySourceInstanceId = { InstanceId(77) },
                )
            assertSoftly {
                destroyedTransient.single().typeList shouldBe listOf(AnnotationType.LayeredEffectDestroyed)
                destroyedPersistent.shouldBeEmpty()
                retired.deletedIds shouldBe listOf(numberedEffect.id)
                val speed = retired.allAnnotations.single(PlayerSpeedDesignationKind::matches)
                speed.affectedIdsList shouldBe listOf(1, 100_000_001)
                detailInt(speed, DetailKeys.VALUE) shouldBe 4
            }
        }
    })

private fun speedResult(annotation: wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo): MechanicAnnotationResult =
    MechanicAnnotationResult(
        transient = emptyList(),
        persistent = emptyList(),
        perKindPersistent = mapOf(PlayerSpeedDesignationKind to listOf(annotation)),
    )

private fun emptyEffectDiff() = EffectTracker.DiffResult(emptyList(), emptyList())

private fun detailInt(
    annotation: wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo,
    key: String,
): Int? =
    annotation.detailsList
        .firstOrNull { it.key == key }
        ?.valueInt32List
        ?.firstOrNull()
