package leyline.game

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.ForgeCardId
import leyline.bridge.InstanceId
import leyline.bridge.SeatId
import leyline.conformance.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class ControllerChangedPipelineTest :
    FunSpec({

        tags(UnitTag)

        fun testResolver(forgeCardId: ForgeCardId): InstanceId = InstanceId(forgeCardId.value + 1000)

        var nextEffect = 7005
        fun testEffectAllocator(): Int = nextEffect++

        beforeTest { nextEffect = 7005 }

        test("steal emits transient ControllerChanged + LayeredEffectCreated + persistent CC+LayeredEffect") {
            val events = listOf(
                GameEvent.SpellResolved(cardId = ForgeCardId(10), hasFizzled = false),
                GameEvent.ControllerChanged(cardId = ForgeCardId(42), oldControllerSeatId = SeatId(2), newControllerSeatId = SeatId(1)),
            )
            val result = MechanicAnnotations.mechanicAnnotations(
                events,
                idResolver = ::testResolver,
                effectIdAllocator = ::testEffectAllocator,
            )

            assertSoftly {
                // Transient: LayeredEffectCreated + ControllerChanged
                result.transient shouldHaveSize 2
                result.transient[0].typeList shouldContain AnnotationType.LayeredEffectCreated
                result.transient[0].affectedIdsList shouldContain 7005 // effect_id
                result.transient[0].affectorId shouldBe 1010 // spell instanceId

                result.transient[1].typeList shouldContain AnnotationType.ControllerChanged
                result.transient[1].affectorId shouldBe 1010
                result.transient[1].affectedIdsList shouldContain 1042 // stolen creature

                // Persistent: ControllerChanged + LayeredEffect with effect_id
                result.persistent shouldHaveSize 1
                result.persistent[0].typeList shouldContain AnnotationType.ControllerChanged
                result.persistent[0].typeList shouldContain AnnotationType.LayeredEffect
                result.persistent[0].affectorId shouldBe 1010
                result.persistent[0].affectedIdsList shouldContain 1042
                result.persistent[0].detailInt("effect_id") shouldBe 7005

                // Tracked effect
                result.controllerChangedEffects shouldHaveSize 1
                result.controllerChangedEffects[0].forgeCardId shouldBe ForgeCardId(42)
                result.controllerChangedEffects[0].effectId shouldBe 7005
            }
        }

        test("revert detected when forgeCardId is in activeStealForgeCardIds") {
            val events = listOf(
                GameEvent.ControllerChanged(cardId = ForgeCardId(42), oldControllerSeatId = SeatId(1), newControllerSeatId = SeatId(2)),
            )
            val result = MechanicAnnotations.mechanicAnnotations(
                events,
                idResolver = ::testResolver,
                effectIdAllocator = ::testEffectAllocator,
                activeStealForgeCardIds = setOf(ForgeCardId(42)),
            )

            assertSoftly {
                // No transient annotations for revert (LayeredEffectDestroyed emitted by computeBatch)
                result.transient.shouldBeEmpty()
                // No new persistent
                result.persistent.shouldBeEmpty()
                // Revert tracked
                result.controllerRevertedForgeCardIds shouldContain ForgeCardId(42)
                // No new steal effects
                result.controllerChangedEffects.shouldBeEmpty()
            }
        }

        test("steal without SpellResolved uses affectorId=0") {
            val events = listOf(
                GameEvent.ControllerChanged(cardId = ForgeCardId(42), oldControllerSeatId = SeatId(2), newControllerSeatId = SeatId(1)),
            )
            val result = MechanicAnnotations.mechanicAnnotations(
                events,
                idResolver = ::testResolver,
                effectIdAllocator = ::testEffectAllocator,
            )

            assertSoftly {
                result.transient shouldHaveSize 2
                // ControllerChanged transient has affectorId=0 (no spell found)
                result.transient[1].affectorId shouldBe 0
                result.persistent shouldHaveSize 1
                result.persistent[0].affectorId shouldBe 0
            }
        }

        test("multiple spells: affector matches nearest preceding SpellResolved") {
            val events = listOf(
                GameEvent.SpellResolved(cardId = ForgeCardId(10), hasFizzled = false),
                GameEvent.ControllerChanged(cardId = ForgeCardId(42), oldControllerSeatId = SeatId(2), newControllerSeatId = SeatId(1)),
                GameEvent.SpellResolved(cardId = ForgeCardId(20), hasFizzled = false),
                GameEvent.ControllerChanged(cardId = ForgeCardId(43), oldControllerSeatId = SeatId(2), newControllerSeatId = SeatId(1)),
            )
            val result = MechanicAnnotations.mechanicAnnotations(
                events,
                idResolver = ::testResolver,
                effectIdAllocator = ::testEffectAllocator,
            )

            assertSoftly {
                result.controllerChangedEffects shouldHaveSize 2
                // First steal affector = spell 10 (iid 1010)
                result.controllerChangedEffects[0].affectorInstanceId shouldBe 1010
                // Second steal affector = spell 20 (iid 1020)
                result.controllerChangedEffects[1].affectorInstanceId shouldBe 1020
            }
        }

        test("effect_id monotonically increases across steals") {
            val events = listOf(
                GameEvent.SpellResolved(cardId = ForgeCardId(10), hasFizzled = false),
                GameEvent.ControllerChanged(cardId = ForgeCardId(42), oldControllerSeatId = SeatId(2), newControllerSeatId = SeatId(1)),
                GameEvent.ControllerChanged(cardId = ForgeCardId(43), oldControllerSeatId = SeatId(2), newControllerSeatId = SeatId(1)),
            )
            val result = MechanicAnnotations.mechanicAnnotations(
                events,
                idResolver = ::testResolver,
                effectIdAllocator = ::testEffectAllocator,
            )

            assertSoftly {
                result.controllerChangedEffects shouldHaveSize 2
                result.controllerChangedEffects[0].effectId shouldBe 7005
                result.controllerChangedEffects[1].effectId shouldBe 7006
                result.controllerChangedEffects[1].effectId shouldBeGreaterThan result.controllerChangedEffects[0].effectId
            }
        }

        test("computeBatch revert removes persistent CC pAnn and returns effect_id") {
            val stolenIid = 1042
            val effectId = 7005
            val affectorIid = 1010

            // Simulate existing persistent annotation from a steal
            val ccPersistent = AnnotationBuilder.controllerChangedEffect(affectorIid, stolenIid, effectId)
                .toBuilder().setId(5).build()
            val active = mapOf(5 to ccPersistent)

            val mechanicResult = MechanicAnnotationResult(
                transient = emptyList(),
                persistent = emptyList(),
                controllerRevertedForgeCardIds = listOf(ForgeCardId(42)),
            )

            val batch = PersistentAnnotationStore.computeBatch(
                currentActive = active,
                startPersistentId = 10,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = mechanicResult,
                resolveInstanceId = ::testResolver,
            )

            assertSoftly {
                batch.allAnnotations.shouldBeEmpty()
                batch.deletedIds shouldContain 5
                batch.revertedEffectIds shouldContain effectId
            }
        }
    })
