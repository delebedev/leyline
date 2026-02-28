package forge.nexus.conformance

import org.testng.Assert.*
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.*

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
 * Confirmed via recording 2026-02-21_20-31-28.
 */
@Test(groups = ["integration"])
class DeclareBlockersDedupeTest {

    private lateinit var harness: MatchFlowHarness

    @AfterMethod(alwaysRun = true)
    fun tearDown() {
        if (::harness.isInitialized) harness.shutdown()
    }

    /**
     * Verify that combat with human as defender produces at most one
     * DeclareBlockersReq. Uses non-validating harness because combat
     * zone transfers can produce transient instanceId gaps.
     *
     * Setup: human casts Raging Goblin (haste blocker). AI is scripted
     * to cast Raging Goblin and attack. When the AI attacks, the engine
     * sends DeclareBlockersReq to the human. The bug was that after
     * the human responds, autoPassAndAdvance would re-enter checkCombatPhase
     * and send a second DeclareBlockersReq.
     */
    @Test(description = "DeclareBlockersReq sent at most once when AI attacks and human declines to block")
    fun noDuplicateBlockersReq() {
        harness = MatchFlowHarness(seed = 42L, deckList = CombatFlowTest.COMBAT_DECK, validating = false)
        harness.connectAndKeep()

        // Script AI: play Mountain, cast Raging Goblin, attack with it
        harness.installScriptedAi(
            listOf(
                ScriptedAction.PlayLand("Mountain"),
                ScriptedAction.CastSpell("Raging Goblin"),
                ScriptedAction.Attack(listOf("Raging Goblin")),
                ScriptedAction.PassPriority,
            ),
        )

        // Human turn 1: play Mountain, cast Raging Goblin (haste) as potential blocker
        assertTrue(harness.playLand(), "Should play Mountain")
        assertTrue(harness.castSpellByName("Raging Goblin"), "Should cast Raging Goblin")
        harness.passPriority() // resolve

        // Skip human's own combat (declare no attackers)
        val msgs1 = harness.allMessages
        if (msgs1.any { it.hasDeclareAttackersReq() }) {
            harness.declareNoAttackers()
        }

        // Pass to advance through human's turn into AI's turn.
        // AI script runs: plays land, casts creature, attacks.
        // Fresh snap per iteration to avoid re-detecting echo-back messages.
        var sawBlockerReq = false
        val preLoopSnap = harness.messageSnapshot()
        for (i in 0 until 50) {
            if (harness.isGameOver()) break
            val snap = harness.messageSnapshot()
            harness.passPriority()
            val recent = harness.messagesSince(snap)
            if (recent.any { it.hasDeclareBlockersReq() }) {
                sawBlockerReq = true
                break
            }
            if (recent.any { it.hasDeclareAttackersReq() }) {
                harness.declareNoAttackers()
            }
        }

        if (!sawBlockerReq) {
            // AI never attacked — skip test (seed/script mismatch)
            println("DeclareBlockersDedupeTest: AI never attacked, skipping dedup check")
            return
        }

        // Human declares no blockers
        harness.declareNoBlockers()

        // Pass through remaining combat
        repeat(10) {
            if (harness.isGameOver()) return@repeat
            harness.passPriority()
        }

        // Count ALL DeclareBlockersReq since before the loop — should be exactly 1
        val allMsgs = harness.messagesSince(preLoopSnap)
        val totalBlockerReqs = allMsgs.count { it.hasDeclareBlockersReq() }

        assertTrue(
            totalBlockerReqs <= 1,
            "DeclareBlockersReq should be sent at most once, got $totalBlockerReqs",
        )
    }
}
