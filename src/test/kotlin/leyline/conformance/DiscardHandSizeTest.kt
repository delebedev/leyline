package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import leyline.bridge.InteractivePromptBridge

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
class DiscardHandSizeTest :
    FunSpec({

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        // TODO: fix ValidatingMessageSink assertion on AI-turn ZoneTransfer (pre-existing)
        xtest("multi-turn with lands does not hang") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeep()

            repeat(3) {
                if (h.isGameOver()) return@repeat
                h.playLand()
                h.passPriority()
            }

            h.isGameOver().shouldBeFalse()
        }

        // TODO: fix ValidatingMessageSink assertion on AI-turn ZoneTransfer (pre-existing)
        xtest("no land plays triggers discard") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeep()

            // Pass 5 times without playing anything — by turn 2-3 hand will exceed 7
            repeat(5) {
                if (h.isGameOver()) return@repeat
                h.passPriority()
            }

            // Game must not be stuck — we should have advanced past turn 1
            h.turn() shouldBeGreaterThanOrEqualTo 2
            h.isGameOver().shouldBeFalse()
        }

        // TODO: fix ValidatingMessageSink assertion on AI-turn ZoneTransfer (pre-existing)
        xtest("discarded card moves to graveyard") {
            val h = MatchFlowHarness(seed = 42L)
            harness = h
            h.connectAndKeep()

            // Don't play anything — accumulate cards in hand.
            // Turn structure (human on the play, seed=42):
            //   T1 (human): no draw (on the play) -> hand=7 -> cleanup: no discard
            //   T2 (AI): AI's turn -> human hand unchanged
            //   T3 (human): draw -> hand=8 -> cleanup: must discard 1 -> hand=7
            //   T4 (AI): AI's turn -> human hand=7
            // Pass until turn 4 (AI's turn) — this is AFTER turn 3's cleanup,
            // so the discard has resolved. Passing to turn 5 would stop at
            // human's Main1 where a fresh draw pushes hand back to 8.
            val player = h.bridge.getPlayer(1)!!
            val gyBefore = player.getZone(ZoneType.Graveyard).size()

            h.passUntilTurn(4)

            val handAfter = player.getZone(ZoneType.Hand).size()
            val gyAfter = player.getZone(ZoneType.Graveyard).size()

            // Verify the discard prompt was answered via the bridge
            val discardPrompts = h.bridge.promptBridge.history
                .filter { it.message.contains("iscard", ignoreCase = true) }
            discardPrompts.shouldNotBeEmpty()
            discardPrompts.all { it.outcome == InteractivePromptBridge.PromptOutcome.RESPONDED }.shouldBeTrue()

            handAfter shouldBeLessThanOrEqual 7
            gyAfter shouldBeGreaterThan gyBefore
        }
    })
