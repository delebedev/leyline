package forge.nexus.conformance

import forge.game.zone.ZoneType
import forge.web.game.InteractivePromptBridge
import org.testng.Assert.*
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test

/**
 * Regression test: when hand exceeds maxHandSize at Cleanup, the engine
 * fires a discard prompt via [WebPlayerController.chooseCardsToDiscardToMaximumHandSize].
 * Before the fix, [TargetingHandler.checkPendingPrompt] only handled targeting
 * prompts (candidateRefs non-empty) — the non-targeting discard prompt was ignored,
 * causing an infinite ActionsAvailableReq loop.
 *
 * The fix adds a non-targeting auto-resolve path in [TargetingHandler.checkPendingPrompt]
 * that answers the discard prompt with defaultIndex (picks the first card).
 *
 * Confirmed via recording 2026-02-21_20-51-31.
 */
@Test(groups = ["integration"])
class DiscardHandSizeTest {

    private lateinit var harness: MatchFlowHarness

    @AfterMethod(alwaysRun = true)
    fun tearDown() {
        if (::harness.isInitialized) harness.shutdown()
    }

    @Test(description = "Game survives 3 turns without hanging when player plays lands (hand <= 7)")
    fun multiTurnWithLandsDoesNotHang() {
        harness = MatchFlowHarness(seed = 42L)
        harness.connectAndKeep()

        repeat(3) {
            if (harness.isGameOver()) return
            harness.playLand()
            harness.passPriority()
        }

        assertFalse(harness.isGameOver(), "Game should survive 3 turns")
    }

    @Test(description = "Game survives when player never plays lands — forces discard-to-hand-size")
    fun noLandPlaysTriggersDiscard() {
        harness = MatchFlowHarness(seed = 42L)
        harness.connectAndKeep()

        // Pass 5 times without playing anything — by turn 2-3 hand will exceed 7
        repeat(5) {
            if (harness.isGameOver()) return
            harness.passPriority()
        }

        // Game must not be stuck — we should have advanced past turn 1
        assertTrue(harness.turn() >= 2, "Should have advanced to at least turn 2, at ${harness.turn()}")
        assertFalse(harness.isGameOver(), "Game should survive 5 pass cycles")
    }

    @Test(description = "Discarded card ends up in graveyard, not stuck in hand")
    fun discardedCardMovesToGraveyard() {
        harness = MatchFlowHarness(seed = 42L)
        harness.connectAndKeep()

        // Don't play anything — accumulate cards in hand.
        // Turn structure (human on the play, seed=42):
        //   T1 (human): no draw (on the play) -> hand=7 -> cleanup: no discard
        //   T2 (AI): AI's turn -> human hand unchanged
        //   T3 (human): draw -> hand=8 -> cleanup: must discard 1 -> hand=7
        //   T4 (AI): AI's turn -> human hand=7
        // Must pass PAST turn 3's cleanup to observe the discard effect.
        val player = harness.bridge.getPlayer(1)!!
        val gyBefore = player.getZone(ZoneType.Graveyard).size()

        harness.passUntilTurn(5)

        val handAfter = player.getZone(ZoneType.Hand).size()
        val gyAfter = player.getZone(ZoneType.Graveyard).size()

        // Verify the discard prompt was answered via the bridge
        val discardPrompts = harness.bridge.promptBridge.history
            .filter { it.message.contains("iscard", ignoreCase = true) }
        assertTrue(discardPrompts.isNotEmpty(), "Should have seen at least one discard prompt")
        assertTrue(
            discardPrompts.all { it.outcome == InteractivePromptBridge.PromptOutcome.RESPONDED },
            "All discard prompts should have been responded to (not timed out)",
        )

        assertTrue(
            handAfter <= 7,
            "Hand should be <= 7 after Cleanup discard, got $handAfter",
        )
        assertTrue(
            gyAfter > gyBefore,
            "Graveyard should have gained cards from discard: was $gyBefore, now $gyAfter",
        )
    }
}
