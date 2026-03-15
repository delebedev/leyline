package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThan
import leyline.IntegrationTag

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
            // Puzzle: AI starts, has Raging Goblin (haste). Human has Burst Lightning + Mountain.
            // AI should attack, combat should resolve without bridge timeout.
            val puzzleText = """
                [metadata]
                Name:AI Combat AutoPass
                Goal:Win
                Turns:3
                Difficulty:Easy
                Description:AI attacks while human has instant in hand

                [state]
                ActivePlayer=AI
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Burst Lightning
                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Raging Goblin|Haste;Mountain
                ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

            val h = MatchFlowHarness(validating = false)
            harness = h

            // Time the entire puzzle setup + combat resolution.
            // Before the fix: bridge timeout fires at 5s per combat phase
            // (COMBAT_DAMAGE + COMBAT_END = 10s minimum).
            // After the fix: completes in < 1s.
            val startTime = System.currentTimeMillis()

            h.connectAndKeepPuzzleText(
                puzzleText,
                aiScript = listOf(
                    ScriptedAction.Attack(listOf("Raging Goblin")),
                    ScriptedAction.PassPriority,
                ),
            )

            // AI starts — auto-pass advances through AI attack.
            // If DeclareBlockersReq appears, respond with no blockers.
            for (i in 0 until 10) {
                if (h.isGameOver()) break
                // Check if we're past AI's turn (on human's turn now)
                if (!h.isAiTurn()) break
                val snap = h.messageSnapshot()
                h.passPriority()
                val recent = h.messagesSince(snap)
                if (recent.any { it.hasDeclareBlockersReq() }) {
                    h.declareNoBlockers()
                }
                if (recent.any { it.hasDeclareAttackersReq() }) {
                    h.declareNoAttackers()
                }
            }

            val elapsed = System.currentTimeMillis() - startTime

            // If the bug were present, elapsed would be >= 5000ms (bridge timeout).
            // With the fix, it should complete in well under 4 seconds.
            elapsed.toInt() shouldBeLessThan 4000
        }
    })
