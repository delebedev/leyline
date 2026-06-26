package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class SyntheticEffectLifecycleTest :
    FunSpec({
        tags(UnitTag)

        test("keeps stable ids until keys disappear") {
            var next = 7002
            val lifecycle = SyntheticEffectLifecycle<String> { next++ }

            val first = lifecycle.getOrAlloc("crew")
            val second = lifecycle.getOrAlloc("crew")
            val otherId = lifecycle.getOrAllocId("reconfigure")
            val released = lifecycle.releaseMissing(setOf("reconfigure"))
            val recreated = lifecycle.getOrAlloc("crew")

            assertSoftly {
                first shouldBe SyntheticEffectLifecycle.Allocation(effectId = 7002, created = true)
                second shouldBe SyntheticEffectLifecycle.Allocation(effectId = 7002, created = false)
                otherId shouldBe 7003
                released shouldBe listOf(7002)
                recreated shouldBe SyntheticEffectLifecycle.Allocation(effectId = 7004, created = true)
            }
        }

        test("clear drops active keys without rewinding allocator") {
            var next = 7002
            val lifecycle = SyntheticEffectLifecycle<Int> { next++ }

            lifecycle.getOrAllocId(1) shouldBe 7002
            lifecycle.clear()
            lifecycle.getOrAllocId(1) shouldBe 7003
        }
    })
