package leyline.game.state

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId

class RevealProxyTrackerTest :
    FunSpec({
        tags(UnitTag)

        test("editor retains ordered active reveal identities") {
            val editor = RevealProxyTracker.Planner(RevealProxyTracker.State(emptyMap()))
            editor.allocate(ForgeCardId(1), InstanceId(100))
            editor.allocate(ForgeCardId(2), InstanceId(101))
            editor.retain(setOf(ForgeCardId(2))) shouldBe listOf(InstanceId(100))
            editor.freeze().entries shouldBe mapOf(ForgeCardId(2) to InstanceId(101))
        }

        test("discarded editor leaves prior value unchanged") {
            val prior = RevealProxyTracker.State(emptyMap())
            RevealProxyTracker.Planner(prior).allocate(ForgeCardId(1), InstanceId(100))
            prior.entries shouldBe emptyMap()
        }
    })
