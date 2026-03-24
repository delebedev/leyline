package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import leyline.IntegrationTag

/**
 * Regression: when the defending player has zero legal blockers, the server
 * should auto-advance through declare blockers instead of sending
 * DeclareBlockersReq to the client (#188).
 */
class ZeroBlockersTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("zero blockers auto-advances without DeclareBlockersReq") {
            // Human has only lands, AI has haste attackers
            val pzl = """
                [metadata]
                Name:Zero Blockers AI Attack
                Goal:Win
                Turns:10
                Difficulty:Easy
                Description:Human has no creatures. AI attacks — should skip blockers.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Plains;Plains
                humanlibrary=Plains;Plains;Plains;Plains;Plains
                aibattlefield=Mountain;Mountain;Raging Goblin;Raging Goblin
                ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
            """.trimIndent()

            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(
                pzl,
                aiScript = listOf(
                    ScriptedAction.Attack(listOf("Raging Goblin")),
                    ScriptedAction.PassPriority,
                ),
            )

            val snap = h.messageSnapshot()

            // Pass through human turn into AI combat
            h.passPriority()

            // Pass through combat — should auto-advance without blockers prompt
            h.passThroughCombat()

            // No DeclareBlockersReq should have been sent
            val msgs = h.messagesSince(snap)
            val blockerReq = msgs.any { it.hasDeclareBlockersReq() }
            blockerReq.shouldBeFalse()

            // Game should still be running (not stuck)
            h.isGameOver().shouldBeFalse()
        }
    })
