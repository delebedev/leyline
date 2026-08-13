package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId

class EarthbendTrackerTest :
    FunSpec({
        tags(UnitTag)

        test("equal Earthbend resolutions refresh neither layers nor allocation") {
            val target = ForgeCardId(20)
            val signature = EarthbendTracker.Signature(timestamp = 10, staticId = 11)
            val facts =
                listOf(
                    EffectProjectionFacts.BattlefieldEarthbendSignature(target, signature),
                )
            val tracker = EarthbendTracker()
            val first = resolution(version = 1)
            val second = resolution(version = 2)
            var nextEffectId = 7002

            tracker.recordResolution(first, 900, 101, 102, facts, { 120 }, { nextEffectId++ })
            val created = tracker.drainFrame(facts)
            tracker.recordResolution(second, 900, 101, 102, facts, { 120 }, { nextEffectId++ })
            val duplicate = tracker.drainFrame(facts)

            assertSoftly {
                created.created
                    .single()
                    .layers.all shouldContainExactly listOf(7002, 7003, 7004, 7005)
                duplicate.created shouldBe emptyList()
                duplicate.active.single().signature shouldBe signature
                nextEffectId shouldBe 7006
            }
        }

        test("discard and stale battlefield signatures do not lose the committed lifecycle") {
            val target = ForgeCardId(20)
            val signature = EarthbendTracker.Signature(timestamp = 10, staticId = 11)
            val facts = listOf(EffectProjectionFacts.BattlefieldEarthbendSignature(target, signature))
            val tracker = EarthbendTracker()
            var nextEffectId = 7002
            tracker.recordResolution(resolution(1), 900, 101, 102, facts, { 120 }, { nextEffectId++ })
            val committed = tracker.drainFrame(facts)

            val discarded = tracker.freeze()
            val stale = tracker.drainFrame(emptyList())

            assertSoftly {
                discarded.activeByTarget[target]?.layers shouldBe committed.active.single().layers
                stale.destroyedLayerIds shouldContainExactly
                    committed.active
                        .single()
                        .layers.all
                stale.active shouldBe emptyList()
            }
        }
    }) {
    companion object {
        private fun resolution(version: Long): EffectProjectionFacts.PendingEarthbendResolution =
            EffectProjectionFacts.PendingEarthbendResolution(
                version = version,
                sourceCardId = ForgeCardId(10),
                sourceAbilityGrpId = 900,
                abilityForgeId = 0,
                targetCardIds = listOf(ForgeCardId(20)),
            )
    }
}
