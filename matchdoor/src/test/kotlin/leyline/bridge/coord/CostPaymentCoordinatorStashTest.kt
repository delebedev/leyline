package leyline.bridge.coord

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

        test("resolveKeywordCostFromStash returns 1 when stash holds true for keyword") {
            val bridge = InteractivePromptBridge(timeoutMs = 0)
            bridge.journal.record(PromptSideEffect.KeywordCostStash(mapOf("Offspring" to true)))
            CostPaymentCoordinator.resolveKeywordCostFromStash(bridge, "Offspring") shouldBe 1
        }

        test("resolveKeywordCostFromStash returns 0 when stash holds false for keyword") {
            val bridge = InteractivePromptBridge(timeoutMs = 0)
            bridge.journal.record(PromptSideEffect.KeywordCostStash(mapOf("Casualty" to false)))
            CostPaymentCoordinator.resolveKeywordCostFromStash(bridge, "Casualty") shouldBe 0
        }

        test("resolveKeywordCostFromStash returns null when keywordName is null") {
            val bridge = InteractivePromptBridge(timeoutMs = 0)
            bridge.journal.record(PromptSideEffect.KeywordCostStash(mapOf("Offspring" to true)))
            CostPaymentCoordinator.resolveKeywordCostFromStash(bridge, null) shouldBe null
        }

        test("resolveKeywordCostFromStash returns null when keyword not in stash (forces confirm-prompt fallback)") {
            val bridge = InteractivePromptBridge(timeoutMs = 0)
            bridge.journal.record(PromptSideEffect.KeywordCostStash(mapOf("Offspring" to true)))
            CostPaymentCoordinator.resolveKeywordCostFromStash(bridge, "Casualty") shouldBe null
        }

        test("resolveKeywordCostFromStash returns null when no stash recorded") {
            val bridge = InteractivePromptBridge(timeoutMs = 0)
            CostPaymentCoordinator.resolveKeywordCostFromStash(bridge, "Offspring") shouldBe null
        }

        test("resolveKeywordCostFromStash is non-draining (Forge re-prompts during cost-prep retries)") {
            val bridge = InteractivePromptBridge(timeoutMs = 0)
            bridge.journal.record(PromptSideEffect.KeywordCostStash(mapOf("Offspring" to true)))
            CostPaymentCoordinator.resolveKeywordCostFromStash(bridge, "Offspring") shouldBe 1
            // Second call returns the same value — peek, not consume.
            CostPaymentCoordinator.resolveKeywordCostFromStash(bridge, "Offspring") shouldBe 1
        }
    })
