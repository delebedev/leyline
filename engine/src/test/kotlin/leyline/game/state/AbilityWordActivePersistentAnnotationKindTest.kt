package leyline.game.state

import forge.game.phase.PhaseType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.MechanicAnnotationResult

class AbilityWordActivePersistentAnnotationKindTest :
    FunSpec({
        tags(UnitTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        test("relational affected-id transition updates in place") {
            val oldAnnotation =
                AnnotationBuilder
                    .abilityWordActive(
                        instanceId = InstanceId(301),
                        abilityWordName = "Opus",
                        affectorId = InstanceId(1),
                        affectedIds = listOf(InstanceId(301), InstanceId(302)),
                    ).toBuilder()
                    .setId(42)
                    .build()
            val incoming =
                AnnotationBuilder.abilityWordActive(
                    instanceId = InstanceId(302),
                    abilityWordName = "Opus",
                    affectorId = InstanceId(1),
                    affectedIds = listOf(InstanceId(302)),
                )

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = mapOf(42 to oldAnnotation),
                    startPersistentId = 100,
                    frame =
                        FrameContext(
                            phase = PhaseType.MAIN1,
                            activePlayerSeat = SeatId(1),
                            battlefieldIids = emptySet(),
                            controllerOf = emptyMap(),
                            stackIids = emptySet(),
                            resolvingStackIids = emptySet(),
                        ),
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult =
                        MechanicAnnotationResult(
                            transient = emptyList(),
                            persistent = emptyList(),
                            perKindPersistent = mapOf(AbilityWordActiveKind to listOf(incoming)),
                        ),
                    resolveInstanceId = { InstanceId(it.value) },
                )

            assertSoftly {
                (42 in result.deletedIds) shouldBe false
                result.allAnnotations.single().id shouldBe 42
                result.allAnnotations.single().affectedIdsList shouldBe listOf(302)
            }
        }
    })
