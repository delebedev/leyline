package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import leyline.IntegrationTag
import leyline.bridge.InteractivePromptBridge
import leyline.bridge.SeatId

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

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("multi-turn with lands does not hang") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeep()

            repeat(3) {
                if (h.isGameOver()) return@repeat
                h.playLand()
                h.passPriority()
            }

            h.isGameOver().shouldBeFalse()
        }

        test("no land plays triggers discard") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
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

        test("discarded card moves to graveyard") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeep()

            // Don't play anything — accumulate cards in hand until discard fires.
            // Pass enough turns that the human's hand exceeds 7 and cleanup discards.
            // The exact turn depends on combat flow (zero-blocker auto-skip can shift
            // turn pacing), so we advance until the discard prompt has been answered
            // rather than targeting a fixed turn number.
            val player = h.bridge.getPlayer(SeatId(1))!!
            val gyBefore = player.getZone(ZoneType.Graveyard).size()

            // Pass up to 8 turns — discard must fire by then (hand grows each draw step)
            repeat(8) {
                if (h.isGameOver()) return@repeat
                h.passPriority()
            }

            // Verify the discard prompt was answered via the bridge
            val discardPrompts = h.bridge.promptBridge(1).history
                .filter { it.message.contains("iscard", ignoreCase = true) }
            discardPrompts.shouldNotBeEmpty()
            discardPrompts.all { it.outcome == InteractivePromptBridge.PromptOutcome.RESPONDED }.shouldBeTrue()

            // After discard resolved, at least one card moved to graveyard
            val gyAfter = player.getZone(ZoneType.Graveyard).size()
            gyAfter shouldBeGreaterThan gyBefore
        }
    })
