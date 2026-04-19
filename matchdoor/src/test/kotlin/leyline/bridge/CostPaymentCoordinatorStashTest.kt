package leyline.bridge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.coord.CostPaymentCoordinator
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptSideEffect

class CostPaymentCoordinatorStashTest :
    FunSpec({

        tags(UnitTag)

        test("consumeStashFor drains OptionalCostStash from journal") {
            val bridge = InteractivePromptBridge(timeoutMs = 0)
            bridge.journal.record(PromptSideEffect.OptionalCostStash(listOf(0, 2)))

            CostPaymentCoordinator.consumeStashFor(bridge) shouldBe listOf(0, 2)
            // Second call drains; no further stash left.
            CostPaymentCoordinator.consumeStashFor(bridge) shouldBe null
        }

        test("consumeStashFor returns null when no stash recorded") {
            val bridge = InteractivePromptBridge(timeoutMs = 0)
            CostPaymentCoordinator.consumeStashFor(bridge) shouldBe null
        }
    })
