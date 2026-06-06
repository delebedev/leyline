package leyline.game.state

import forge.game.phase.PhaseType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.BoardTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.MechanicAnnotationResult
import leyline.game.state.EffectTracker
import leyline.game.state.FrameContext
import leyline.game.state.PersistentAnnotationStore

/**
 * Unit pins for the [leyline.game.state.PersistentAnnotationKind] registry.
 * Covers lifecycle expiry behavior — EZTT clears at the controller's
 * Upkeep, ColorProduction clears when the source iid leaves the
 * battlefield — and registry-driven upsert dispatch.
 *
 * The conformance harness exercises the expiry path end-to-end; these
 * unit pins keep the rule legible and fast-failing in isolation.
 */
class PersistentAnnotationKindTest :
    FunSpec({
        tags(BoardTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
        }

        fun frame(
            phase: PhaseType?,
            battlefield: Set<Int> = emptySet(),
            activeSeat: SeatId = SeatId(1),
            controllerOf: Map<Int, SeatId> = emptyMap(),
        ): FrameContext =
            FrameContext(
                phase = phase,
                activePlayerSeat = activeSeat,
                battlefieldIids = battlefield,
                controllerOf = controllerOf,
            )

        fun emptyMechanicResult(): MechanicAnnotationResult =
            MechanicAnnotationResult(
                transient = emptyList(),
                persistent = emptyList(),
            )

        fun colorProductionResult(vararg annotations: wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo): MechanicAnnotationResult =
            MechanicAnnotationResult(
                transient = emptyList(),
                persistent = emptyList(),
                perKindPersistent = mapOf(ColorProductionKind to annotations.toList()),
            )

        fun manaDetailsResult(vararg annotations: wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo): MechanicAnnotationResult =
            MechanicAnnotationResult(
                transient = emptyList(),
                persistent = emptyList(),
                perKindPersistent = mapOf(ManaDetailsKind to annotations.toList()),
            )

        fun annotationDetailInt(
            ann: wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo,
            key: String,
        ): Int? =
            ann.detailsList
                .firstOrNull { it.key == key }
                ?.valueInt32List
                ?.firstOrNull()

        val emptyEffectDiff = EffectTracker.DiffResult(emptyList(), emptyList())

        test("EZTT clears at Upkeep") {
            val eztt =
                AnnotationBuilder
                    .enteredZoneThisTurn(zoneId = 7, instanceId = InstanceId(101))
                    .toBuilder()
                    .setId(42)
                    .build()
            val active = mapOf(42 to eztt)

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = active,
                    startPersistentId = 100,
                    frame = frame(PhaseType.UPKEEP),
                    effectPersistent = emptyList(),
                    effectDiff = emptyEffectDiff,
                    transferPersistent = emptyList(),
                    mechanicResult = emptyMechanicResult(),
                    resolveInstanceId = { InstanceId(it.value) },
                )

            assertSoftly {
                result.deletedIds shouldContain 42
                result.allAnnotations.shouldBeEmpty()
            }
        }

        test("EZTT survives non-Upkeep phases") {
            val eztt =
                AnnotationBuilder
                    .enteredZoneThisTurn(zoneId = 7, instanceId = InstanceId(101))
                    .toBuilder()
                    .setId(42)
                    .build()
            val active = mapOf(42 to eztt)

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = active,
                    startPersistentId = 100,
                    frame = frame(PhaseType.MAIN1),
                    effectPersistent = emptyList(),
                    effectDiff = emptyEffectDiff,
                    transferPersistent = emptyList(),
                    mechanicResult = emptyMechanicResult(),
                    resolveInstanceId = { InstanceId(it.value) },
                )

            assertSoftly {
                result.deletedIds.shouldBeEmpty()
                result.allAnnotations shouldHaveSize 1
                result.allAnnotations[0].id shouldBe 42
            }
        }

        test("ColorProduction clears when source iid leaves the battlefield") {
            val cp =
                AnnotationBuilder
                    .colorProduction(instanceId = InstanceId(101), colors = listOf(4))
                    .toBuilder()
                    .setId(7)
                    .build()
            val active = mapOf(7 to cp)

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = active,
                    startPersistentId = 100,
                    frame = frame(PhaseType.MAIN1, battlefield = emptySet()),
                    effectPersistent = emptyList(),
                    effectDiff = emptyEffectDiff,
                    transferPersistent = emptyList(),
                    mechanicResult = emptyMechanicResult(),
                    resolveInstanceId = { InstanceId(it.value) },
                )

            assertSoftly {
                result.deletedIds shouldContain 7
                result.allAnnotations.shouldBeEmpty()
            }
        }

        test("ColorProduction survives when source iid is still on the battlefield") {
            val cp =
                AnnotationBuilder
                    .colorProduction(instanceId = InstanceId(101), colors = listOf(4))
                    .toBuilder()
                    .setId(7)
                    .build()
            val active = mapOf(7 to cp)

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = active,
                    startPersistentId = 100,
                    frame = frame(PhaseType.MAIN1, battlefield = setOf(101)),
                    effectPersistent = emptyList(),
                    effectDiff = emptyEffectDiff,
                    transferPersistent = emptyList(),
                    mechanicResult = colorProductionResult(cp),
                    resolveInstanceId = { InstanceId(it.value) },
                )

            assertSoftly {
                result.deletedIds.shouldBeEmpty()
                result.allAnnotations shouldHaveSize 1
            }
        }

        test("ManaDetails keeps same manaId from different sources") {
            val first = AnnotationBuilder.manaDetails(sourceInstanceId = InstanceId(101), manaId = 10)
            val second = AnnotationBuilder.manaDetails(sourceInstanceId = InstanceId(202), manaId = 10)

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = emptyMap(),
                    startPersistentId = 100,
                    frame = frame(PhaseType.MAIN1),
                    effectPersistent = emptyList(),
                    effectDiff = emptyEffectDiff,
                    transferPersistent = emptyList(),
                    mechanicResult = manaDetailsResult(first, second),
                    resolveInstanceId = { InstanceId(it.value) },
                )

            assertSoftly {
                result.deletedIds.shouldBeEmpty()
                result.allAnnotations.map { it.affectorId to it.affectedIdsList.single() }
                    .shouldContainExactlyInAnyOrder(101 to 10, 202 to 10)
            }
        }

        test("EZTT arriving same Upkeep frame survives — expiry runs before transfer-originated additions") {
            val freshEztt =
                AnnotationBuilder.enteredZoneThisTurn(zoneId = 7, instanceId = InstanceId(101))

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = emptyMap(),
                    startPersistentId = 100,
                    frame = frame(PhaseType.UPKEEP),
                    effectPersistent = emptyList(),
                    effectDiff = emptyEffectDiff,
                    transferPersistent = listOf(freshEztt),
                    mechanicResult = emptyMechanicResult(),
                    resolveInstanceId = { InstanceId(it.value) },
                )

            // Expiry pass (step 0) saw nothing in active. Transfer pass (step 2)
            // added the new EZTT after expiry — survives this frame, expires next Upkeep.
            assertSoftly {
                result.deletedIds.shouldBeEmpty()
                result.allAnnotations shouldHaveSize 1
                result.allAnnotations[0].id shouldBe 100
            }
        }

        test("Stale EZTTs from prior turns expire on next Upkeep — basic two-frame sequence") {
            // Frame N: card enters. EZTT lands.
            val freshEztt =
                AnnotationBuilder.enteredZoneThisTurn(zoneId = 7, instanceId = InstanceId(101))
            val frame1 =
                PersistentAnnotationStore.computeBatch(
                    currentActive = emptyMap(),
                    startPersistentId = 100,
                    frame = frame(PhaseType.MAIN1),
                    effectPersistent = emptyList(),
                    effectDiff = emptyEffectDiff,
                    transferPersistent = listOf(freshEztt),
                    mechanicResult = emptyMechanicResult(),
                    resolveInstanceId = { InstanceId(it.value) },
                )
            frame1.allAnnotations shouldHaveSize 1
            val ezttId = frame1.allAnnotations[0].id

            // Frame N+1 (next-turn Upkeep): expiry fires.
            val carriedActive = frame1.allAnnotations.associateBy { it.id }
            val frame2 =
                PersistentAnnotationStore.computeBatch(
                    currentActive = carriedActive,
                    startPersistentId = frame1.nextPersistentId,
                    frame = frame(PhaseType.UPKEEP),
                    effectPersistent = emptyList(),
                    effectDiff = emptyEffectDiff,
                    transferPersistent = emptyList(),
                    mechanicResult = emptyMechanicResult(),
                    resolveInstanceId = { InstanceId(it.value) },
                )

            assertSoftly {
                frame2.deletedIds shouldContainExactlyInAnyOrder listOf(ezttId)
                frame2.allAnnotations.shouldBeEmpty()
            }
        }

        test("INERT FrameContext leaves EZTT alone — preserves legacy callers that don't pass a frame") {
            // INERT has phase=null so EZTT survives. ColorProduction is excluded from this
            // test because it is pruned when absent from the incoming source snapshot.
            val eztt =
                AnnotationBuilder
                    .enteredZoneThisTurn(zoneId = 7, instanceId = InstanceId(101))
                    .toBuilder()
                    .setId(42)
                    .build()
            val active = mapOf(42 to eztt)

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = active,
                    startPersistentId = 100,
                    frame = FrameContext.INERT,
                    effectPersistent = emptyList(),
                    effectDiff = emptyEffectDiff,
                    transferPersistent = emptyList(),
                    mechanicResult = emptyMechanicResult(),
                    resolveInstanceId = { InstanceId(it.value) },
                )

            assertSoftly {
                result.deletedIds.shouldBeEmpty()
                result.allAnnotations shouldHaveSize 1
            }
        }

        test("EZTT clears only for cards controlled by the active player at Upkeep") {
            // Two cards, one controlled by each seat. Seat 2 is active; only seat-2's
            // EZTT should expire. Pre-fix this test would fail (both EZTTs expired).
            val seat1Eztt =
                AnnotationBuilder
                    .enteredZoneThisTurn(zoneId = 28, instanceId = InstanceId(101))
                    .toBuilder()
                    .setId(40)
                    .build()
            val seat2Eztt =
                AnnotationBuilder
                    .enteredZoneThisTurn(zoneId = 28, instanceId = InstanceId(202))
                    .toBuilder()
                    .setId(41)
                    .build()
            val active = mapOf(40 to seat1Eztt, 41 to seat2Eztt)

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = active,
                    startPersistentId = 100,
                    frame =
                        frame(
                            phase = PhaseType.UPKEEP,
                            activeSeat = SeatId(2),
                            controllerOf =
                                mapOf(
                                    101 to SeatId(1),
                                    202 to SeatId(2),
                                ),
                        ),
                    effectPersistent = emptyList(),
                    effectDiff = emptyEffectDiff,
                    transferPersistent = emptyList(),
                    mechanicResult = emptyMechanicResult(),
                    resolveInstanceId = { InstanceId(it.value) },
                )

            assertSoftly {
                result.deletedIds shouldContainExactlyInAnyOrder listOf(41)
                result.allAnnotations.map { it.id } shouldContain 40
            }
        }

        test("EZTT for a card already off-objects expires on any Upkeep — prevents stale accumulation") {
            val eztt =
                AnnotationBuilder
                    .enteredZoneThisTurn(zoneId = 28, instanceId = InstanceId(999))
                    .toBuilder()
                    .setId(50)
                    .build()
            val active = mapOf(50 to eztt)

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = active,
                    startPersistentId = 100,
                    // Card 999 is NOT in controllerOf — already off-objects.
                    frame = frame(phase = PhaseType.UPKEEP, controllerOf = emptyMap()),
                    effectPersistent = emptyList(),
                    effectDiff = emptyEffectDiff,
                    transferPersistent = emptyList(),
                    mechanicResult = emptyMechanicResult(),
                    resolveInstanceId = { InstanceId(it.value) },
                )

            assertSoftly {
                result.deletedIds shouldContain 50
                result.allAnnotations.shouldBeEmpty()
            }
        }

        test("AbilityWordActive value transition emits delete-old + add-new in the same BatchResult") {
            // Regression guard for leyline-ety5: the deletion ID flows through
            // BatchResult.deletedIds and is drained directly into the GSM at
            // build time. If a refactor reintroduces the old "queue, drain
            // next frame" plumbing, the deletion would shift to the next
            // batch's result and this test would fail.
            val oldAnn =
                AnnotationBuilder
                    .abilityWordActive(
                        instanceId = InstanceId(101),
                        abilityWordName = "Threshold",
                        value = 6,
                        threshold = 7,
                    ).toBuilder()
                    .setId(42)
                    .build()
            val active = mapOf(42 to oldAnn)

            val incoming =
                AnnotationBuilder.abilityWordActive(
                    instanceId = InstanceId(101),
                    abilityWordName = "Threshold",
                    value = 7,
                    threshold = 7,
                )
            val mechanicResult =
                MechanicAnnotationResult(
                    transient = emptyList(),
                    persistent = emptyList(),
                    perKindPersistent =
                        mapOf(leyline.game.state.AbilityWordActiveKind to listOf(incoming)),
                )

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = active,
                    startPersistentId = 100,
                    frame = frame(PhaseType.MAIN1),
                    effectPersistent = emptyList(),
                    effectDiff = emptyEffectDiff,
                    transferPersistent = emptyList(),
                    mechanicResult = mechanicResult,
                    resolveInstanceId = { InstanceId(it.value) },
                )

            assertSoftly {
                // Old annotation id was deleted in the same batch.
                result.deletedIds shouldContain 42
                // A new annotation with the updated value is in the active set.
                val newAnn = result.allAnnotations.firstOrNull { it.id != 42 }
                newAnn shouldNotBe null
                newAnn!!.detailsList.any { it.key == "value" && it.valueInt32List.firstOrNull() == 7 } shouldBe true
            }
        }

        test("Resolving an EZTT for a card on a player's BF doesn't accidentally also expire ColorProduction") {
            // Combined-state scenario — make sure each kind's shouldExpire is independent.
            val eztt =
                AnnotationBuilder
                    .enteredZoneThisTurn(zoneId = 7, instanceId = InstanceId(101))
                    .toBuilder()
                    .setId(42)
                    .build()
            val cp =
                AnnotationBuilder
                    .colorProduction(instanceId = InstanceId(202), colors = listOf(4))
                    .toBuilder()
                    .setId(43)
                    .build()
            val active = mapOf(42 to eztt, 43 to cp)

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = active,
                    startPersistentId = 100,
                    frame = frame(PhaseType.UPKEEP, battlefield = setOf(202)),
                    effectPersistent = emptyList(),
                    effectDiff = emptyEffectDiff,
                    transferPersistent = emptyList(),
                    mechanicResult = colorProductionResult(cp),
                    resolveInstanceId = { ForgeCardId(it.value).let { _ -> InstanceId(it.value) } },
                )

            assertSoftly {
                // EZTT expires (Upkeep) but ColorProduction survives (source still on BF).
                result.deletedIds shouldContainExactlyInAnyOrder listOf(42)
                result.allAnnotations.map { it.id } shouldContain 43
            }
        }

        test("CommanderDesignation replaces tax updates and prunes absent rows") {
            val playerRow =
                AnnotationBuilder
                    .commanderPlayerDesignation(SeatId(1), GrpId(92302), listOf(1, 4), costIncrease = 0)
                    .toBuilder()
                    .setId(42)
                    .build()
            val objectRow =
                AnnotationBuilder
                    .commanderObjectDesignation(InstanceId(101), GrpId(92302), listOf(1, 4), costIncrease = 0)
                    .toBuilder()
                    .setId(43)
                    .build()
            val active = mapOf(42 to playerRow, 43 to objectRow)
            val taxedRows =
                listOf(
                    AnnotationBuilder.commanderPlayerDesignation(SeatId(1), GrpId(92302), listOf(1, 4), costIncrease = 2),
                    AnnotationBuilder.commanderObjectDesignation(InstanceId(101), GrpId(92302), listOf(1, 4), costIncrease = 2),
                )

            val updated =
                PersistentAnnotationStore.computeBatch(
                    currentActive = active,
                    startPersistentId = 100,
                    frame = frame(PhaseType.MAIN1),
                    effectPersistent = emptyList(),
                    effectDiff = emptyEffectDiff,
                    transferPersistent = emptyList(),
                    mechanicResult =
                        MechanicAnnotationResult(
                            transient = emptyList(),
                            persistent = emptyList(),
                            perKindPersistent = mapOf(CommanderDesignationKind to taxedRows),
                        ),
                    resolveInstanceId = { InstanceId(it.value) },
                )

            assertSoftly {
                updated.deletedIds shouldContainExactlyInAnyOrder listOf(42, 43)
                updated.allAnnotations shouldHaveSize 2
                updated.allAnnotations.map { annotationDetailInt(it, "CostIncrease") } shouldContainExactlyInAnyOrder listOf(2, 2)
            }

            val pruned =
                PersistentAnnotationStore.computeBatch(
                    currentActive = active,
                    startPersistentId = 100,
                    frame = frame(PhaseType.MAIN1),
                    effectPersistent = emptyList(),
                    effectDiff = emptyEffectDiff,
                    transferPersistent = emptyList(),
                    mechanicResult = emptyMechanicResult(),
                    resolveInstanceId = { InstanceId(it.value) },
                )

            assertSoftly {
                pruned.deletedIds shouldContainExactlyInAnyOrder listOf(42, 43)
                pruned.allAnnotations.shouldBeEmpty()
            }
        }
    })
