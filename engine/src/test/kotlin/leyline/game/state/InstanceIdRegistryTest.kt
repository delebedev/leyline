package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId

class InstanceIdRegistryTest :
    FunSpec({
        tags(UnitTag)

        test("editor allocates stable bidirectional identities") {
            val editor = InstanceIdRegistry.Planner(InstanceIdRegistry.initialState())
            assertSoftly {
                editor.getOrAlloc(ForgeCardId(1)) shouldBe InstanceId(100)
                editor.getOrAlloc(ForgeCardId(1)) shouldBe InstanceId(100)
                editor.getForgeCardId(InstanceId(100)) shouldBe ForgeCardId(1)
                editor.freeze().nextInstanceId shouldBe 101
            }
        }

        test("discarded editor leaves prior value unchanged") {
            val prior = InstanceIdRegistry.initialState()
            InstanceIdRegistry.Planner(prior).getOrAlloc(ForgeCardId(1))
            prior shouldBe InstanceIdRegistry.initialState()
        }

        test("reallocation retains reverse history and advances active identity") {
            val editor = InstanceIdRegistry.Planner(InstanceIdRegistry.initialState())
            editor.getOrAlloc(ForgeCardId(1))
            editor.realloc(ForgeCardId(1)) shouldBe InstanceIdRegistry.IdReallocation(InstanceId(100), InstanceId(101))
            editor.freeze().instanceIdToForgeId shouldBe
                mapOf(InstanceId(100) to ForgeCardId(1), InstanceId(101) to ForgeCardId(1))
        }
    })
