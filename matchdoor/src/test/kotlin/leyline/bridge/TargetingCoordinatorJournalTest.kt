package leyline.bridge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.coord.TargetingCoordinator
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId

class TargetingCoordinatorJournalTest :
    FunSpec({

        tags(UnitTag)

        // --- legend rule ---

        test("recordLegendVictim writes to journal") {
            val bridge = InteractivePromptBridge(timeoutMs = 0)
            TargetingCoordinator.recordLegendVictim(bridge, ForgeCardId(42))
            bridge.journal.consumeLegendVictim(ForgeCardId(42)) shouldBe true
        }

        test("recordLegendVictim accumulates multiple victims") {
            val bridge = InteractivePromptBridge(timeoutMs = 0)
            TargetingCoordinator.recordLegendVictim(bridge, ForgeCardId(1))
            TargetingCoordinator.recordLegendVictim(bridge, ForgeCardId(2))
            bridge.journal.consumeLegendVictim(ForgeCardId(2)) shouldBe true
            bridge.journal.consumeLegendVictim(ForgeCardId(1)) shouldBe true
        }

        // --- search to hand ---

        test("recordSearchedToHand writes to journal") {
            val bridge = InteractivePromptBridge(timeoutMs = 0)
            TargetingCoordinator.recordSearchedToHand(bridge, ForgeCardId(13))
            bridge.journal.consumeSearched(ForgeCardId(13)) shouldBe true
        }

        test("multiple recordSearchedToHand calls accumulate per id") {
            val bridge = InteractivePromptBridge(timeoutMs = 0)
            TargetingCoordinator.recordSearchedToHand(bridge, ForgeCardId(1))
            TargetingCoordinator.recordSearchedToHand(bridge, ForgeCardId(2))
            bridge.journal.consumeSearched(ForgeCardId(1)) shouldBe true
            bridge.journal.consumeSearched(ForgeCardId(2)) shouldBe true
        }

        // --- reveal lifecycle ---

        test("startReveal records RevealStarted on journal") {
            val bridge = InteractivePromptBridge(timeoutMs = 0)
            val ids = listOf(ForgeCardId(1), ForgeCardId(2))
            TargetingCoordinator.startReveal(bridge, ids, SeatId(1))
            val started = bridge.journal.activeReveal()
            started?.allHandCardIds shouldBe ids
            started?.ownerSeatId shouldBe SeatId(1)
        }

        test("endReveal clears journal (records RevealEnded)") {
            val bridge = InteractivePromptBridge(timeoutMs = 0)
            TargetingCoordinator.startReveal(bridge, listOf(ForgeCardId(1)), SeatId(1))
            TargetingCoordinator.endReveal(bridge)
            bridge.journal.activeReveal().shouldBeNull()
        }

        test("endReveal on a bridge with no active reveal is a no-op") {
            val bridge = InteractivePromptBridge(timeoutMs = 0)
            TargetingCoordinator.endReveal(bridge)
            bridge.journal.activeReveal().shouldBeNull()
        }
    })
