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

        test("reserved reallocation retains reverse history and advances active identity") {
            val editor = InstanceIdRegistry.Planner(InstanceIdRegistry.initialState())
            editor.getOrAlloc(ForgeCardId(1))
            val reserved = editor.reserve()
            editor.reallocTo(ForgeCardId(1), reserved) shouldBe
                InstanceIdRegistry.IdReallocation(InstanceId(100), InstanceId(101))
            editor.freeze().instanceIdToForgeId shouldBe
                mapOf(InstanceId(100) to ForgeCardId(1), InstanceId(101) to ForgeCardId(1))
        }

        for (reserved in listOf(false, true)) {
            test("reallocation with reserved destination $reserved advances all aliases without changing another lifetime") {
                val editor = InstanceIdRegistry.Planner(InstanceIdRegistry.initialState())
                val root = ForgeCardId(1)
                val admitted = ForgeCardId(2)
                val independent = ForgeCardId(3)
                val independentAlias = ForgeCardId(4)
                val rootId = editor.alias(root, admitted)
                val independentId = editor.alias(independent, independentAlias)
                val prior = editor.freeze()
                val change = if (reserved) editor.reallocTo(admitted, editor.reserve()) else editor.realloc(admitted)
                assertSoftly {
                    change.old shouldBe rootId
                    editor.getOrAlloc(root) shouldBe change.new
                    editor.getOrAlloc(admitted) shouldBe change.new
                    editor.getOrAlloc(independent) shouldBe independentId
                    editor.getOrAlloc(independentAlias) shouldBe independentId
                    editor.getForgeCardId(change.old) shouldBe admitted
                    editor.getForgeCardId(change.new) shouldBe admitted
                    prior.forgeIdToInstanceId[root] shouldBe rootId
                    prior.forgeIdToInstanceId[admitted] shouldBe rootId
                }
            }
        }

        test("reallocating the root retains an admitted alias and leaves prior values immutable") {
            val original = InstanceIdRegistry.Planner(InstanceIdRegistry.initialState())
            val root = ForgeCardId(1)
            val admitted = ForgeCardId(2)
            val old = original.alias(root, admitted)
            val prior = original.freeze()
            val editor = InstanceIdRegistry.Planner(prior)
            val change = editor.realloc(root)
            assertSoftly {
                editor.getOrAlloc(root) shouldBe change.new
                editor.getOrAlloc(admitted) shouldBe change.new
                editor.getForgeCardId(old) shouldBe admitted
                editor.getForgeCardId(change.new) shouldBe root
                prior.forgeIdToInstanceId shouldBe mapOf(root to old, admitted to old)
            }
        }
    })
