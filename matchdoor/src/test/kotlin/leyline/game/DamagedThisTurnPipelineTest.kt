package leyline.game

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
import leyline.bridge.SeatId
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.Step

/**
 * Pipeline tests for `DamagedThisTurn` — the persistent creature-damaged badge.
 * Covers routing to the persistent bucket, Battlefield-zone affectorId,
 * per-turn accumulation of victim `affectedIds`, and clear on Upkeep.
 */
class DamagedThisTurnPipelineTest :
    FunSpec({

        tags(UnitTag)

        fun idResolver(forgeCardId: ForgeCardId): InstanceId = InstanceId(forgeCardId.value + 1000)

        // -- CombatAnnotations: routing + shape --

        test("combatAnnotations emits DamagedThisTurn to persistent bucket, not transient") {
            val events = listOf(
                GameEvent.DamageDealtToCard(sourceCardId = ForgeCardId(1), targetCardId = ForgeCardId(2), amount = 2),
            )
            val result = CombatAnnotations.combatAnnotations(
                events = events,
                idResolver = ::idResolver,
                previousLifeTotals = mapOf(1 to 20, 2 to 20),
                currentLifeTotals = mapOf(1 to 20, 2 to 20),
            )

            result.annotations.none { ann ->
                ann.typeList.any { it == AnnotationType.DamagedThisTurn }
            }.shouldBeTrue()

            result.damagedThisTurnPersistent.size shouldBe 1
            val dtt = result.damagedThisTurnPersistent[0]
            assertSoftly {
                dtt.typeList shouldContain AnnotationType.DamagedThisTurn
                dtt.affectorId shouldBe AnnotationConstants.BATTLEFIELD_ZONE_AFFECTOR.value
                dtt.affectedIdsList shouldBe listOf(idResolver(ForgeCardId(2)).value)
            }
        }

        test("combatAnnotations collapses multiple victims into a single annotation") {
            val events = listOf(
                GameEvent.DamageDealtToCard(sourceCardId = ForgeCardId(1), targetCardId = ForgeCardId(2), amount = 2),
                GameEvent.DamageDealtToCard(sourceCardId = ForgeCardId(3), targetCardId = ForgeCardId(4), amount = 1),
            )
            val result = CombatAnnotations.combatAnnotations(
                events = events,
                idResolver = ::idResolver,
                previousLifeTotals = emptyMap(),
                currentLifeTotals = emptyMap(),
            )

            result.damagedThisTurnPersistent.size shouldBe 1
            result.damagedThisTurnPersistent[0].affectedIdsList shouldBe listOf(1002, 1004)
        }

        test("combatAnnotations detects Upkeep phase transition as clear signal") {
            val events = listOf(
                GameEvent.PhaseChanged(
                    seatId = SeatId(1),
                    phase = 1,
                    step = Step.Upkeep_a2cb.number,
                ),
            )
            val result = CombatAnnotations.combatAnnotations(
                events = events,
                idResolver = ::idResolver,
                previousLifeTotals = emptyMap(),
                currentLifeTotals = emptyMap(),
            )

            result.clearDamagedThisTurn.shouldBeTrue()
            result.damagedThisTurnPersistent.shouldBeEmpty()
        }

        test("combatAnnotations does not signal clear on non-Upkeep phase changes") {
            val events = listOf(
                GameEvent.PhaseChanged(
                    seatId = SeatId(1),
                    phase = 1,
                    step = Step.Draw_a2cb.number,
                ),
            )
            val result = CombatAnnotations.combatAnnotations(
                events = events,
                idResolver = ::idResolver,
                previousLifeTotals = emptyMap(),
                currentLifeTotals = emptyMap(),
            )

            result.clearDamagedThisTurn.shouldBeFalse()
        }

        // -- PersistentAnnotationStore: grow-or-create + clear --

        test("computeBatch creates DamagedThisTurn pAnn when none exists") {
            val incoming = AnnotationBuilder.damagedThisTurn(affectedIds = listOf(1002.iid))
            val result = PersistentAnnotationStore.computeBatch(
                currentActive = emptyMap(),
                startPersistentId = 10,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = MechanicAnnotationResult(emptyList(), emptyList()),
                combatResult = CombatAnnotationResult(
                    annotations = emptyList(),
                    damagedThisTurnPersistent = listOf(incoming),
                ),
                resolveInstanceId = ::idResolver,
            )

            result.allAnnotations.size shouldBe 1
            val ann = result.allAnnotations[0]
            assertSoftly {
                ann.typeList shouldContain AnnotationType.DamagedThisTurn
                ann.id shouldBe 10
                ann.affectorId shouldBe AnnotationConstants.BATTLEFIELD_ZONE_AFFECTOR.value
                ann.affectedIdsList shouldBe listOf(1002)
                result.deletedIds.shouldBeEmpty()
            }
        }

        test("computeBatch grows affectedIds when DamagedThisTurn already active, keeps same id") {
            val existing = AnnotationBuilder.damagedThisTurn(affectedIds = listOf(1002.iid))
                .toBuilder().setId(7).build()
            val active = mapOf(7 to existing)
            val incoming = AnnotationBuilder.damagedThisTurn(affectedIds = listOf(1003.iid))

            val result = PersistentAnnotationStore.computeBatch(
                currentActive = active,
                startPersistentId = 20,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = MechanicAnnotationResult(emptyList(), emptyList()),
                combatResult = CombatAnnotationResult(
                    annotations = emptyList(),
                    damagedThisTurnPersistent = listOf(incoming),
                ),
                resolveInstanceId = ::idResolver,
            )

            result.allAnnotations.size shouldBe 1
            val merged = result.allAnnotations[0]
            assertSoftly {
                merged.id shouldBe 7
                merged.affectedIdsList shouldBe listOf(1002, 1003)
                result.deletedIds.shouldBeEmpty()
                result.nextPersistentId shouldBe 20
            }
        }

        test("computeBatch deduplicates overlapping victims on grow") {
            val existing = AnnotationBuilder.damagedThisTurn(affectedIds = listOf(1002.iid))
                .toBuilder().setId(7).build()
            val active = mapOf(7 to existing)
            val incoming = AnnotationBuilder.damagedThisTurn(affectedIds = listOf(1002.iid, 1003.iid))

            val result = PersistentAnnotationStore.computeBatch(
                currentActive = active,
                startPersistentId = 20,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = MechanicAnnotationResult(emptyList(), emptyList()),
                combatResult = CombatAnnotationResult(
                    annotations = emptyList(),
                    damagedThisTurnPersistent = listOf(incoming),
                ),
                resolveInstanceId = ::idResolver,
            )

            result.allAnnotations[0].affectedIdsList shouldBe listOf(1002, 1003)
        }

        test("computeBatch clears DamagedThisTurn on upkeep signal") {
            val existing = AnnotationBuilder.damagedThisTurn(affectedIds = listOf(1002.iid, 1003.iid))
                .toBuilder().setId(7).build()
            val active = mapOf(7 to existing)

            val result = PersistentAnnotationStore.computeBatch(
                currentActive = active,
                startPersistentId = 20,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = MechanicAnnotationResult(emptyList(), emptyList()),
                combatResult = CombatAnnotationResult(
                    annotations = emptyList(),
                    clearDamagedThisTurn = true,
                ),
                resolveInstanceId = ::idResolver,
            )

            result.allAnnotations.shouldBeEmpty()
            result.deletedIds shouldBe listOf(7)
        }

        test("computeBatch clear on upkeep, then create for new turn's damage — fresh id") {
            // Step 1: existing pAnn from previous turn
            val existing = AnnotationBuilder.damagedThisTurn(affectedIds = listOf(1002.iid))
                .toBuilder().setId(7).build()
            val clearResult = PersistentAnnotationStore.computeBatch(
                currentActive = mapOf(7 to existing),
                startPersistentId = 20,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = MechanicAnnotationResult(emptyList(), emptyList()),
                combatResult = CombatAnnotationResult(
                    annotations = emptyList(),
                    clearDamagedThisTurn = true,
                ),
                resolveInstanceId = ::idResolver,
            )
            clearResult.allAnnotations.shouldBeEmpty()
            clearResult.deletedIds shouldBe listOf(7)

            // Step 2: new turn's damage — active is empty now; fresh id issued
            val incoming = AnnotationBuilder.damagedThisTurn(affectedIds = listOf(1004.iid))
            val createResult = PersistentAnnotationStore.computeBatch(
                currentActive = emptyMap(),
                startPersistentId = 21,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = MechanicAnnotationResult(emptyList(), emptyList()),
                combatResult = CombatAnnotationResult(
                    annotations = emptyList(),
                    damagedThisTurnPersistent = listOf(incoming),
                ),
                resolveInstanceId = ::idResolver,
            )
            assertSoftly {
                createResult.allAnnotations.size shouldBe 1
                createResult.allAnnotations[0].id shouldBe 21
                createResult.allAnnotations[0].id shouldNotBe 7
            }
        }
    })
