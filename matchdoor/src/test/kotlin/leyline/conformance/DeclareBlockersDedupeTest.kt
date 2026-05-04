package leyline.conformance

import io.kotest.matchers.ints.shouldBeLessThanOrEqual

/**
 * Regression test: DeclareBlockersReq must be sent at most once per
 * declare-blockers step. Before the fix, [CombatHandler.checkCombatPhase]
 * re-triggered during the priority window after blockers were submitted,
 * sending a duplicate DeclareBlockersReq that stalled the client.
 *
 * The fix adds a [CombatHandler.pendingBlockersSent] flag that prevents
 * re-entry: set in sendDeclareBlockersReq, checked in checkCombatPhase,
 * cleared in onDeclareBlockers.
 *
 * Confirmed via reference session 2026-02-21_20-31-28.
 *
 * Seeded mid-turn: AI at COMBAT_DECLARE_ATTACKERS with attacker already
 * marked, human has a potential blocker. Engine advances through attackers
 * step → DeclareBlockersReq auto-fires. No two-turn walkthrough needed.
 */
class DeclareBlockersDedupeTest :
    SessionTest({

        test("no duplicate blockers req") {
            startPuzzle(
                """
            ActivePlayer=AI
            ActivePhase=COMBAT_DECLARE_ATTACKERS
            HumanLife=20
            AILife=20

            humanbattlefield=Mountain;Raging Goblin
            humanlibrary=Mountain;Mountain;Mountain
            aibattlefield=Mountain;Raging Goblin|Attacking|Tapped
            ailibrary=Mountain;Mountain;Mountain
            """,
                name = "DeclareBlockers Dedup",
                turns = 1,
            )

            // declareNoBlockers drives autoPassAndAdvance internally; any re-entry
            // DeclareBlockersReq would fire inside that synchronous call.
            harness.declareNoBlockers()

            val totalBlockerReqs = harness.allMessages.count { it.hasDeclareBlockersReq() }
            totalBlockerReqs shouldBeLessThanOrEqual 1
        }
    })
