package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId

class SyntheticEffectProjectionTest :
    FunSpec({
        tags(UnitTag)

        test("equal tentative planners allocate every lifecycle family identically without advancing committed state") {
            val baseline = SyntheticEffectProjection.initial()

            fun compile(): SyntheticEffectProjection {
                val planner = SyntheticEffectProjection.Planner(baseline)
                planner.effects
                    .emitInitEffectsOnce()
                    .created
                    .map { it.syntheticId } shouldBe listOf(7002, 7003, 7004)
                planner.effects.diffBoosts(
                    mapOf(101 to listOf(EffectTracker.BoostEntry(timestamp = 1, staticId = 2, power = 1, toughness = 1))),
                )
                planner.effects.diffKeywords(
                    mapOf(101 to listOf(EffectTracker.KeywordEntry(timestamp = 3, staticId = 4, keyword = "Flying"))),
                )
                planner.crew.getOrAllocId(ForgeCardId(11))
                planner.reconfigure.getOrAlloc(ForgeCardId(12))
                planner.mutate.getOrAlloc(13 to 14)
                return planner.freeze()
            }

            val first = compile()
            val second = compile()

            assertSoftly {
                first shouldBe second
                baseline shouldBe SyntheticEffectProjection.initial()
                first.effects.nextId shouldBe 7010
                first.crew.active shouldBe mapOf(ForgeCardId(11) to 7007)
                first.reconfigure.active shouldBe mapOf(ForgeCardId(12) to 7008)
                first.mutate.active shouldBe mapOf((13 to 14) to 7009)
            }
        }

        test("discarded planner retains Earthbend lifecycle output") {
            val active =
                EarthbendTracker.Active(
                    targetForgeCardId = ForgeCardId(21),
                    targetInstanceId = 121,
                    sourceInstanceId = 122,
                    sourceCardGrpId = 123,
                    sourceAbilityGrpId = 124,
                    resolvingInstanceId = 125,
                    signature = EarthbendTracker.Signature(timestamp = 1, staticId = 2),
                    layers = EarthbendTracker.LayerIds(type = 7002, haste = 7003, power = 7004, toughness = 7005),
                    uniqueAbilityId = 200,
                )
            val baseline =
                SyntheticEffectProjection.initial().copy(
                    earthbend =
                        EarthbendTracker.State(
                            activeByTarget = mapOf(active.targetForgeCardId to active),
                            pendingDestroyedLayerIds = listOf(7001),
                            pendingCreated = listOf(active),
                            nextUniqueAbilityId = 201,
                        ),
                )

            val discarded = SyntheticEffectProjection.Planner(baseline).freeze()

            assertSoftly {
                discarded shouldBe baseline
                baseline.earthbend.pendingDestroyedLayerIds shouldBe listOf(7001)
                baseline.earthbend.pendingCreated shouldBe listOf(active)
            }
        }
    })
