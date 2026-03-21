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
            // → engine advances to DECLARE_BLOCKERS → DeclareBlockersReq sent to
            // human → STOP. Test responds with declareNoBlockers, then combat
            // advances through COMBAT_DAMAGE and COMBAT_END where the fix must
            // downgrade SEND_STATE on AI turns — otherwise the client gets stuck
            // (no Pass button → 120s timeout).
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

            val h = MatchFlowHarness()
            harness = h

            // Time the combat resolution (excluding card DB init).
            // Before the fix: bridge timeout fires at 5s per combat phase
            // (COMBAT_DAMAGE + COMBAT_END = 10s minimum).
            // After the fix: completes in < 1s.
            h.connectAndKeepPuzzleText(puzzleText)
            val startTime = System.currentTimeMillis()

            // onPuzzleStart stopped at DeclareBlockersReq — human must respond.
            // Decline to block so combat advances through DAMAGE → END.
            h.declareNoBlockers()

            // Pass through remaining combat phases into human's turn.
            h.passThroughCombat()

            val elapsed = System.currentTimeMillis() - startTime

            val humanPlayer = h.bridge.getPlayer(SeatId(1))!!

            // If the bug were present, elapsed would be >= 5000ms (bridge timeout).
            // With the fix, it should complete in well under 4 seconds.
            elapsed.toInt() shouldBeLessThan 4000

            // Positive assertion: combat damage was dealt.
            // Raging Goblin (1/1) attacked unblocked → human took 1 damage.
            (humanPlayer.life < 20).shouldBeTrue()
        }
    })
