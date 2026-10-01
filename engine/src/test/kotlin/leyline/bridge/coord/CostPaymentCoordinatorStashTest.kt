package leyline.bridge.coord

import forge.game.GameActionUtil
import forge.game.zone.ZoneType
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.SeatId
import leyline.testkit.BoardTest
import leyline.testkit.hand

class CostPaymentCoordinatorStashTest :
    BoardTest({
        test("optional costs require a recorded choice and consume it once") {
            val board = startWithBoard { _, human, _ -> addCard("Into the Roil", human, ZoneType.Hand) }
            val ability =
                board.human.hand
                    .card("Into the Roil")
                    .getSpells()
                    .first()
            val costs = GameActionUtil.getOptionalCostValues(ability).toMutableList()
            costs.size shouldBe 1
            val journal = board.bridge.promptBridge(SeatId(1)).journal

            board.human.controller.chooseOptionalCosts(ability, costs) shouldBe emptyList()
            journal.record(PromptSideEffect.OptionalCostStash(listOf(0)))
            board.human.controller.chooseOptionalCosts(ability, costs) shouldBe costs
            board.human.controller.chooseOptionalCosts(ability, costs) shouldBe emptyList()
            journal.record(PromptSideEffect.OptionalCostStash(emptyList()))
            board.human.controller.chooseOptionalCosts(ability, costs) shouldBe emptyList()
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
