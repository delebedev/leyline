package leyline.conformance

import forge.game.zone.ZoneType
import org.testng.Assert.*
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test

/**
 * End-to-end blocker declaration tests: AI attacks, human blocks.
 *
 * DISABLED: multi-turn setup takes 28-42s per test (~110s total for 2 enabled).
 * Needs puzzle-based rewrite: start with creatures on BF, script AI to attack.
 * Attempted puzzle migration but ScriptedPlayerController.declareAttackers()
 * doesn't fire in puzzle mode — autoPassAndAdvance skips through AI combat
 * without CombatHandler emitting DeclareBlockersReq. Root cause: puzzle-mode
 * AI controller interaction with AutoPassEngine needs investigation.
 *
 * Verifies:
 * - DeclareBlockersReq sent when AI attacks and human has eligible blockers
 * - Blocker assignments (human creature blocks AI creature) resolve correctly
 * - Declining to block lets damage through to human life
 * - 1/1 trading produces creature deaths (zone transfers)
 *
 * Uses non-validating harness: combat zone transfers can produce transient
 * instanceId gaps (known StateMapper issue tracked separately).
 */
@Test(groups = ["integration"], enabled = false)
class BlockerDeclarationTest {

    private lateinit var harness: MatchFlowHarness

    @AfterMethod(alwaysRun = true)
    fun tearDown() {
        if (::harness.isInitialized) harness.shutdown()
    }

    /**
     * Setup: human casts Raging Goblin turn 1 (potential blocker).
     * AI scripted to cast Raging Goblin and attack with it on its turn.
     * Advances to the point where DeclareBlockersReq should be sent.
     *
     * Returns pair of (humanBlockerInstanceId, aiAttackerInstanceId).
     */
    private fun setupAiAttacksHumanCanBlock(): Pair<Int, Int> {
        harness = MatchFlowHarness(seed = 42L, deckList = CombatFlowTest.COMBAT_DECK, validating = false)
        harness.connectAndKeep()

        // AI: play Mountain, cast Raging Goblin, attack with it
        harness.installScriptedAi(
            listOf(
                ScriptedAction.PlayLand("Mountain"),
                ScriptedAction.CastSpell("Raging Goblin"),
                ScriptedAction.Attack(listOf("Raging Goblin")),
                ScriptedAction.PassPriority,
                ScriptedAction.PlayLand("Mountain"),
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
            ),
        )

        // Human turn 1: play Mountain, cast Raging Goblin (haste → potential blocker)
        assertTrue(harness.playLand(), "Should play Mountain")
        assertTrue(harness.castSpellByName("Raging Goblin"), "Should cast Raging Goblin")
        harness.passPriority() // resolve

        // Skip human's own combat if prompted
        if (harness.allMessages.any { it.hasDeclareAttackersReq() }) {
            harness.declareNoAttackers()
        }

        // Get human blocker instanceId before combat
        val humanCreatures = harness.humanBattlefieldCreatures()
        assertTrue(humanCreatures.isNotEmpty(), "Human should have Raging Goblin on BF")
        val blockerIid = humanCreatures.first().first

        // Advance to AI's turn — AI script runs: land, cast, attack.
        // Use a fresh snap per iteration to avoid re-detecting handled messages
        // (echo-back from declareNoAttackers produces DeclareAttackersReq).
        var sawBlockerReq = false
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

        assertTrue(sawBlockerReq, "Should receive DeclareBlockersReq when AI attacks")

        // Find the AI attacker instanceId from the DeclareBlockersReq
        val blockReq = harness.allMessages.last { it.hasDeclareBlockersReq() }.declareBlockersReq
        assertTrue(blockReq.blockersCount > 0, "DeclareBlockersReq should list eligible blockers")

        // The blocker should reference attacker instanceIds
        val blocker = blockReq.blockersList.first { it.blockerInstanceId == blockerIid }
        assertTrue(blocker.attackerInstanceIdsCount > 0, "Blocker should have legal attacker targets")
        val attackerIid = blocker.attackerInstanceIdsList.first()

        return blockerIid to attackerIid
    }

    // --- Test 1: humanBlocksAiAttacker ---

    // TODO: flaky — setupAiAttacksHumanCanBlock loop doesn't reliably reach
    //  DeclareBlockersReq within iteration budget. AI script timing is seed-sensitive.
    //  Pre-existing issue (fails 1/3 on old code too). Needs deterministic puzzle-based setup.
    @Test(description = "Human blocks AI's 1/1 attacker with 1/1 blocker; both trade", enabled = false)
    fun humanBlocksAiAttacker() {
        val (blockerIid, attackerIid) = setupAiAttacksHumanCanBlock()

        // Human life before blocking
        val humanPlayer = harness.bridge.getPlayer(1)!!
        val lifeBefore = humanPlayer.life

        // Declare block: human's Raging Goblin blocks AI's Raging Goblin
        harness.declareBlockers(mapOf(blockerIid to attackerIid))

        // Pass through remaining combat
        repeat(15) {
            if (harness.isGameOver()) return@repeat
            harness.passPriority()
        }

        // Human life should NOT decrease (blocked damage)
        val lifeAfter = humanPlayer.life
        assertEquals(lifeAfter, lifeBefore, "Human life should not change when 1/1 is fully blocked")

        // Both 1/1s should have traded — human's creature should be in graveyard
        val humanGy = humanPlayer.getZone(ZoneType.Graveyard).cards
        val blockerInGy = humanGy.any { it.name == "Raging Goblin" }
        assertTrue(blockerInGy, "Human's Raging Goblin should be in graveyard after trade")

        assertFalse(harness.isGameOver(), "Game should not be over")
    }

    // --- Test 2: humanDeclinesBlockingTakesDamage ---

    @Test(description = "Human declines to block AI's 1/1 attacker; human takes 1 damage")
    fun humanDeclinesBlockingTakesDamage() {
        setupAiAttacksHumanCanBlock() // advances to DeclareBlockersReq

        val humanPlayer = harness.bridge.getPlayer(1)!!
        val lifeBefore = humanPlayer.life

        // Human declines to block
        harness.declareNoBlockers()

        // Pass through remaining combat
        repeat(15) {
            if (harness.isGameOver()) return@repeat
            harness.passPriority()
        }

        // Human should have taken exactly 1 damage (Raging Goblin is 1/1)
        val lifeAfter = humanPlayer.life
        assertEquals(
            lifeAfter,
            lifeBefore - 1,
            "Human should take exactly 1 damage from unblocked Raging Goblin",
        )

        // Human's creature should still be alive
        val humanBf = harness.humanBattlefieldCreatures()
        assertTrue(humanBf.isNotEmpty(), "Human's creature should survive (was not involved in combat)")

        assertFalse(harness.isGameOver(), "Game should not be over")
    }

    // --- Test 3: tradeProducesCreatureDeaths ---

    @Test(description = "1/1 blocks 1/1: both creatures end up in graveyard")
    fun tradeProducesCreatureDeaths() {
        val (blockerIid, attackerIid) = setupAiAttacksHumanCanBlock()

        // Declare block
        harness.declareBlockers(mapOf(blockerIid to attackerIid))

        // Pass through remaining combat
        repeat(15) {
            if (harness.isGameOver()) return@repeat
            harness.passPriority()
        }

        // Both creatures should be dead
        val humanPlayer = harness.bridge.getPlayer(1)!!
        val aiPlayer = harness.bridge.getPlayer(2)!!

        val humanGy = humanPlayer.getZone(ZoneType.Graveyard).cards
        val aiGy = aiPlayer.getZone(ZoneType.Graveyard).cards

        val humanGoblinDead = humanGy.any { it.name == "Raging Goblin" }
        val aiGoblinDead = aiGy.any { it.name == "Raging Goblin" }

        assertTrue(humanGoblinDead, "Human's Raging Goblin should be in graveyard after trade")
        assertTrue(aiGoblinDead, "AI's Raging Goblin should be in graveyard after trade")

        assertFalse(harness.isGameOver(), "Game should not be over after 1/1 trade")
    }
}
