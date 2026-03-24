package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeLessThan
import leyline.IntegrationTag
import leyline.bridge.SeatId

/**
 * Regression test for #120: combat priority hang when human has instants.
 *
 * Before the fix, [AutoPassEngine] offered Cast actions during AI combat
 * (COMBAT_DAMAGE, COMBAT_END) via the SEND_STATE safety net. The client
 * rendered combat but showed no Pass button — causing a 120s bridge timeout.
 *
 * Real server never offers actions during AI combat phases (actionsCount=0
 * in GSMs, confirmed across proxy recordings). The fix downgrades SEND_STATE
 * to fall-through on AI turns, matching real server behavior.
 *
 * The puzzle starts at COMBAT_DECLARE_ATTACKERS with the AI already attacking.
 * This directly exercises the fixed code path — no dependence on the AI
 * deciding to enter combat.
 *
 * Uses `AiConfig(speed = 0.0)` to disable GamePlayback pacing sleeps on the
 * engine thread. Without this, each combat phase transition sleeps 400ms for
 * client animation, making the test appear slow even when the fix works.
 */
class AiCombatAutoPassTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("AI combat auto-passes when human has castable instant") {
            // Puzzle: AI's turn at COMBAT_DECLARE_ATTACKERS. AI has a Raging Goblin
            // marked |Attacking|Tapped. Human has Burst Lightning + untapped Mountain.
            //
            // Flow: onPuzzleStart → autoPassAndAdvance → checkCombatPhase at
            // DECLARE_ATTACKERS (AI turn, attackers present) → SEND_STATE downgraded
            // → engine advances to DECLARE_BLOCKERS → zero legal blockers detected
            // → empty declaration auto-submitted → combat advances through
            // COMBAT_DAMAGE and COMBAT_END where the fix must downgrade SEND_STATE
            // on AI turns — otherwise the client gets stuck (no Pass button →
            // 120s timeout).
            val puzzleText = """
                [metadata]
                Name:AI Combat AutoPass
                Goal:Win
                Turns:3
                Difficulty:Easy
                Description:AI attacks while human has instant in hand

                [state]
                ActivePlayer=AI
                ActivePhase=COMBAT_DECLARE_ATTACKERS
                HumanLife=20
                AILife=20

                humanhand=Burst Lightning
                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Raging Goblin|Attacking|Tapped;Mountain
                ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

            val h = MatchFlowHarness(validating = false)
            harness = h

            // Time the full puzzle connect — with zero-blocker auto-skip, combat
            // resolves entirely during onPuzzleStart. Before the fix, AI combat
            // phases would timeout at 5s each.
            val startTime = System.currentTimeMillis()
            h.connectAndKeepPuzzleText(puzzleText)
            val elapsed = System.currentTimeMillis() - startTime

            val humanPlayer = h.bridge.getPlayer(SeatId(1))!!

            // If the bug were present, elapsed would be >= 10000ms (multiple bridge timeouts).
            // With the fix, it should complete well under 8 seconds (includes card DB init).
            elapsed.toInt() shouldBeLessThan 8000

            // Positive assertion: combat damage was dealt.
            // Raging Goblin (1/1) attacked unblocked → human took 1 damage.
            (humanPlayer.life < 20).shouldBeTrue()
        }
    })
