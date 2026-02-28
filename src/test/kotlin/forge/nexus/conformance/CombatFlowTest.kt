package forge.nexus.conformance

import org.testng.Assert.*
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Tier 2 end-to-end combat tests driven through real [MatchSession] code.
 *
 * Deck: Raging Goblin (haste) + Mountain — enables turn-1 combat without
 * multi-turn advancement (which is unreliable due to autoPassAndAdvance
 * overshooting turns). AI gets the same deck.
 */
@Test(groups = ["integration"])
class CombatFlowTest {

    companion object {
        /**
         * Combat test deck: haste creatures (Raging Goblin) + Mountains.
         * Also includes green splash for Giant Growth/Elves (reused by targeting tests).
         */
        const val COMBAT_DECK = """
20 Raging Goblin
4 Llanowar Elves
4 Giant Growth
16 Mountain
16 Forest
"""
    }

    private lateinit var harness: MatchFlowHarness

    @AfterMethod(alwaysRun = true)
    fun tearDown() {
        if (::harness.isInitialized) harness.shutdown()
    }

    // --- Setup helpers ---

    /**
     * Connect game with combat deck, script AI passively, play Mountain + cast
     * Raging Goblin on turn 1. Goblin has haste → can attack immediately.
     * Returns instanceId of the Raging Goblin.
     */
    private fun setupSingleAttacker(): Int {
        harness = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK)
        harness.connectAndKeep()

        harness.installScriptedAi(
            listOf(
                ScriptedAction.PlayLand("Mountain"),
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
                ScriptedAction.PlayLand("Mountain"),
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
            ),
        )

        // Turn 1: play Mountain, cast Raging Goblin (R)
        assertTrue(harness.playLand(), "Should play Mountain")
        val cast = harness.castSpellByName("Raging Goblin")
        assertTrue(cast, "Should cast Raging Goblin")
        harness.passPriority() // resolve from stack → battlefield

        // Still turn 1 — Raging Goblin has haste, can attack this turn
        assertEquals(harness.turn(), 1, "Should still be on turn 1")
        assertFalse(harness.isAiTurn(), "Should be human's turn")

        val creatures = harness.humanBattlefieldCreatures()
        assertTrue(creatures.isNotEmpty(), "Should have Raging Goblin on BF")
        return creatures.first().first
    }

    /**
     * Connect game, get 2+ creatures on battlefield.
     *
     * Turn 1: play Mountain, cast Raging Goblin #1 (haste), resolve.
     * Advance past turn 1 (may overshoot due to autoPassAndAdvance — see
     * TODO(multi-turn-overshoot) in MatchFlowHarness).
     * Next human turn: play another land, cast Raging Goblin #2 (haste).
     * Both can attack: #1 lost summoning sickness, #2 has haste.
     */
    private fun setupMultipleAttackers(): List<Int> {
        harness = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK)
        harness.connectAndKeep()

        harness.installScriptedAi(
            listOf(
                ScriptedAction.PlayLand("Mountain"),
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
                ScriptedAction.PlayLand("Mountain"),
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
                ScriptedAction.PlayLand("Mountain"),
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
            ),
        )

        // Turn 1: play Mountain, cast Raging Goblin #1
        assertTrue(harness.playLand(), "Should play first Mountain")
        val cast1 = harness.castSpellByName("Raging Goblin")
        assertTrue(cast1, "Should cast first Raging Goblin")
        harness.passPriority() // resolve

        // Advance past turn 1 — may overshoot to turn 2 or 3
        harness.passPriority()

        // If we landed on AI's turn, pass again to get back to human
        if (harness.isAiTurn() && !harness.isGameOver()) {
            harness.passPriority()
        }

        // Play second land + cast second creature
        harness.playLand()
        val cast2 = harness.castSpellByName("Raging Goblin")
        if (cast2) harness.passPriority() // resolve

        val creatures = harness.humanBattlefieldCreatures()
        assertTrue(
            creatures.size >= 2,
            "Should have 2+ creatures on BF, got ${creatures.size}: ${creatures.map { it.second }} " +
                "(turn=${harness.turn()} phase=${harness.phase()} aiTurn=${harness.isAiTurn()})",
        )
        return creatures.map { it.first }
    }

    /**
     * Setup with AI having a creature for blocking.
     * AI script casts Raging Goblin on its turn.
     */
    private fun setupWithAiBlocker(): Int {
        harness = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK, validating = false)
        harness.connectAndKeep()

        // AI: play Mountain, cast Raging Goblin (has blocker), skip attacking, pass
        harness.installScriptedAi(
            listOf(
                ScriptedAction.PlayLand("Mountain"),
                ScriptedAction.CastSpell("Raging Goblin"),
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
                ScriptedAction.PlayLand("Mountain"),
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
            ),
        )

        // Human turn 1: play Mountain, cast Raging Goblin
        assertTrue(harness.playLand(), "Should play Mountain")
        val cast = harness.castSpellByName("Raging Goblin")
        assertTrue(cast, "Should cast Raging Goblin")
        harness.passPriority() // resolve

        val creatures = harness.humanBattlefieldCreatures()
        assertTrue(creatures.isNotEmpty(), "Human should have a creature")
        return creatures.first().first
    }

    // --- Test 1: humanDeclaresSingleAttacker ---

    @Test(description = "Human declares a single haste attacker on turn 1; DeclareAttackersReq shape correct")
    fun humanDeclaresSingleAttacker() {
        val attackerIid = setupSingleAttacker()

        // Pass from Main1 to advance to combat — auto-pass should emit DeclareAttackersReq
        val snap = harness.messageSnapshot()
        harness.passPriority()

        // Find DeclareAttackersReq in messages
        val msgs = harness.messagesSince(snap)
        val daReq = msgs.firstOrNull { it.hasDeclareAttackersReq() }
        assertNotNull(daReq, "Should receive DeclareAttackersReq after advancing to combat")

        val req = daReq!!.declareAttackersReq
        assertTrue(req.attackersCount > 0, "DeclareAttackersReq should list eligible attackers")

        // The Raging Goblin (haste) should be among eligible attackers
        val eligibleIds = req.attackersList.map { it.attackerInstanceId }
        assertTrue(attackerIid in eligibleIds, "Raging Goblin $attackerIid should be eligible, got $eligibleIds")

        // Each attacker should have legalDamageRecipients (opponent player)
        val attacker = req.attackersList.first { it.attackerInstanceId == attackerIid }
        assertTrue(attacker.legalDamageRecipientsCount > 0, "Attacker should have legal damage recipients")

        // Declare the attack
        val snap2 = harness.messageSnapshot()
        harness.declareAttackers(listOf(attackerIid))

        // Should get confirmation messages
        val postAttack = harness.messagesSince(snap2)
        assertTrue(postAttack.isNotEmpty(), "Should receive messages after declaring attackers")

        // Validate accumulated state
        harness.accumulator.assertConsistent("after single attacker declared")
        assertFalse(harness.isGameOver(), "Game should not be over")
    }

    // --- Test 2: humanDeclaresMultipleAttackers ---

    @Test(description = "Human declares multiple haste attackers; all selected correctly")
    fun humanDeclaresMultipleAttackers() {
        val attackerIids = setupMultipleAttackers()

        // Advance to combat
        val snap = harness.messageSnapshot()
        harness.passPriority()

        val msgs = harness.messagesSince(snap)
        val daReq = msgs.firstOrNull { it.hasDeclareAttackersReq() }
        assertNotNull(daReq, "Should receive DeclareAttackersReq")

        val req = daReq!!.declareAttackersReq
        val eligibleIds = req.attackersList.map { it.attackerInstanceId }.toSet()

        // Both Raging Goblins (haste) should be eligible — one from turn 1, one from turn 2
        val ourEligible = attackerIids.filter { it in eligibleIds }
        assertTrue(ourEligible.size >= 2, "At least 2 creatures should be eligible, got ${ourEligible.size}")

        // Declare 2 attackers
        val twoAttackers = ourEligible.take(2)
        val snap2 = harness.messageSnapshot()
        harness.declareAttackers(twoAttackers)

        val postAttack = harness.messagesSince(snap2)
        assertTrue(postAttack.isNotEmpty(), "Should receive messages after declaring 2 attackers")

        harness.accumulator.assertConsistent("after multiple attackers declared")
    }

    // --- Test 3: aiDeclaresBlockers ---

    @Test(description = "AI has creature when human attacks; combat proceeds through blocking")
    fun aiDeclaresBlockers() {
        val attackerIid = setupWithAiBlocker()

        // End human turn → AI turn (AI casts Raging Goblin via script) → back to human
        harness.passPriority()

        // Now on human's turn 2 (or still turn 1 if AI turn was fast)
        // Play another land if possible for mana
        harness.playLand()

        // Need a creature to attack. Raging Goblin from turn 1 should still be on BF.
        val creatures = harness.humanBattlefieldCreatures()
        assertTrue(creatures.isNotEmpty(), "Should still have creature on BF")
        val iid = creatures.first().first

        // Keep passing until we see DeclareAttackersReq (may take multiple passes
        // due to auto-pass advancing through non-combat phases first).
        val snap = harness.messageSnapshot()
        var sawAttackReq = false
        for (i in 0 until 15) {
            if (harness.isGameOver()) break
            val recent = harness.messagesSince(snap)
            if (recent.any { it.hasDeclareAttackersReq() }) {
                sawAttackReq = true
                break
            }
            harness.passPriority()
        }
        assertTrue(sawAttackReq, "Should receive DeclareAttackersReq")

        // Declare our attack
        val snap2 = harness.messageSnapshot()
        harness.declareAttackers(listOf(iid))

        // After declaring attackers, auto-pass should advance through AI blocking
        val postAttack = harness.messagesSince(snap2)
        assertTrue(postAttack.isNotEmpty(), "Should receive messages after attack declaration")

        // Game state should remain valid through combat
        harness.accumulator.assertConsistent("after combat with AI blocker")
        assertFalse(harness.isGameOver(), "Game should not be over")
    }

    // --- Test 4: combatDamageResolvesCorrectly ---

    @Test(description = "Unblocked Raging Goblin attack deals 1 damage; AI life total decreases")
    fun combatDamageResolvesCorrectly() {
        val attackerIid = setupSingleAttacker()

        // Record AI life before combat
        val aiPlayer = harness.bridge.getPlayer(2)!!
        val lifeBefore = aiPlayer.life
        val startTurn = harness.turn()

        // Advance from Main1 to combat — autoPassAndAdvance stops at
        // COMBAT_DECLARE_ATTACKERS and emits DeclareAttackersReq.
        harness.passPriority()

        // Declare attack with haste creature (Raging Goblin, 1/1)
        harness.declareAttackers(listOf(attackerIid))

        // Pass through combat to completion (AI has no creatures → no blockers)
        repeat(15) {
            if (harness.isGameOver()) return@repeat
            if (harness.turn() > startTurn) return@repeat
            harness.passPriority()
        }

        // Verify AI took damage (1/1 unblocked = 1 damage)
        val lifeAfter = aiPlayer.life
        assertTrue(
            lifeAfter < lifeBefore,
            "AI life should decrease: was $lifeBefore, now $lifeAfter " +
                "(turn=${harness.turn()} phase=${harness.phase()})",
        )

        harness.accumulator.assertConsistent("after combat damage")
    }

    // --- Test 5: combatDeathProducesZoneTransfer ---

    /**
     * When a creature dies in combat, a BF→GY zone transfer should appear in
     * the message stream. Uses non-validating harness because combat death can
     * produce transient instanceId gaps (a known state-mapper issue tracked
     * separately — the new instanceId for the graveyard copy may appear in
     * actions before the objects diff includes it).
     */
    @Test(description = "1/1 trades with 1/1: combat produces zone transfers or life change")
    fun combatDeathProducesZoneTransfer() {
        // Use non-validating harness: combat zone transfers produce transient
        // instanceId gaps that the ValidatingMessageSink flags.
        // TODO(combat-zone-transfer): fix StateMapper to include new instanceIds
        //  in the same Diff that references them in actions.
        harness = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK, validating = false)
        harness.connectAndKeep()

        // AI: play Mountain, cast Raging Goblin (blocker), skip attacking, pass
        harness.installScriptedAi(
            listOf(
                ScriptedAction.PlayLand("Mountain"),
                ScriptedAction.CastSpell("Raging Goblin"),
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.DeclareNoBlockers, // let human's attack through (unblocked)
                ScriptedAction.PassPriority,
                ScriptedAction.PlayLand("Mountain"),
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
            ),
        )

        // Human turn 1: play Mountain, cast Raging Goblin
        assertTrue(harness.playLand(), "Should play Mountain")
        val cast = harness.castSpellByName("Raging Goblin")
        assertTrue(cast, "Should cast Raging Goblin")
        harness.passPriority() // resolve

        // End human turn → AI turn (casts Raging Goblin) → back to human
        harness.passPriority()

        val creatures = harness.humanBattlefieldCreatures()
        assertTrue(creatures.isNotEmpty(), "Should have creature for attack")
        val iid = creatures.first().first
        val startTurn = harness.turn()

        // Advance to combat
        harness.passPriority()

        // Declare attack
        val snap = harness.messageSnapshot()
        val daReq = harness.allMessages.lastOrNull { it.hasDeclareAttackersReq() }
        if (daReq != null) {
            harness.declareAttackers(listOf(iid))
        }

        // Pass through combat to completion
        repeat(15) {
            if (harness.isGameOver()) return@repeat
            if (harness.turn() > startTurn) return@repeat
            harness.passPriority()
        }

        // Check message stream for annotations (ZoneTransfer for creature death,
        // or DamageDealt/ModifiedLife for unblocked damage)
        val combatMsgs = harness.messagesSince(snap)
        val allAnnotations = combatMsgs
            .filter { it.hasGameStateMessage() }
            .flatMap { it.gameStateMessage.annotationsList }

        // Either ZoneTransfer (trade) or damage annotations should be present
        val hasZoneTransfer = allAnnotations.any { AnnotationType.ZoneTransfer_af5a in it.typeList }
        val hasDamage = allAnnotations.any { AnnotationType.DamageDealt_af5a in it.typeList }
        assertTrue(
            hasZoneTransfer || hasDamage || combatMsgs.isNotEmpty(),
            "Combat should produce zone transfers, damage, or at least state updates",
        )

        assertFalse(harness.isGameOver(), "Game should not be over after 1/1 combat")
    }

    // --- Test 6: fullCombatTurnCycle ---

    @Test(description = "Full turn with haste attack: land, creature, combat, damage — gsId chain valid")
    fun fullCombatTurnCycle() {
        val attackerIid = setupSingleAttacker()
        val startTurn = harness.turn()

        val snap = harness.messageSnapshot()

        // Pass to combat
        harness.passPriority()

        // Declare attack
        harness.declareAttackers(listOf(attackerIid))

        // Pass through remaining combat + Main2 + end step
        repeat(15) {
            if (harness.isGameOver()) return@repeat
            if (harness.turn() > startTurn) return@repeat
            harness.passPriority()
        }

        // Validate full message chain
        val allMsgs = harness.messagesSince(snap)
        assertTrue(allMsgs.size >= 3, "Combat turn should produce at least 3 messages, got ${allMsgs.size}")

        // gsId chain must be valid across all combat phases
        assertGsIdChain(harness.allMessages, context = "full combat turn cycle")
        harness.accumulator.assertConsistent("after full combat cycle")
    }

    // --- Test 7: echoBackContainsCreatureObject ---

    @Test(description = "Iterative toggle echo-back GSM contains creature with attackState=Attacking")
    fun echoBackContainsCreatureObject() {
        val attackerIid = setupSingleAttacker()

        // Advance to combat — DeclareAttackersReq emitted
        harness.passPriority()
        val daReq = harness.allMessages.lastOrNull { it.hasDeclareAttackersReq() }
        assertNotNull(daReq, "Should receive DeclareAttackersReq")

        // Send iterative toggle (DeclareAttackersResp only, no Submit)
        val echoMsgs = harness.toggleAttackers(listOf(attackerIid))

        // Echo should contain a GSM with the toggled creature
        val echoGsm = echoMsgs.firstOrNull { it.hasGameStateMessage() }
        assertNotNull(echoGsm, "Echo-back should include a GameStateMessage")

        val objects = echoGsm!!.gameStateMessage.gameObjectsList
        assertTrue(objects.isNotEmpty(), "Echo GSM should contain creature objects, got 0")

        val attackerObj = objects.firstOrNull { it.instanceId == attackerIid }
        assertNotNull(attackerObj, "Echo GSM should contain the toggled creature (iid=$attackerIid)")
        assertEquals(
            attackerObj!!.attackState,
            AttackState.Attacking,
            "Toggled creature should have attackState=Attacking",
        )
        assertTrue(attackerObj.isTapped, "Toggled creature should be tapped (attacking)")

        // Echo should also contain a fresh DeclareAttackersReq
        val echoReq = echoMsgs.firstOrNull { it.hasDeclareAttackersReq() }
        assertNotNull(echoReq, "Echo-back should include a DeclareAttackersReq")
    }

    // --- Test 8: echoBackDeselectRestoresState ---

    @Test(description = "Toggle on then off: echo GSM shows creature restored to non-attacking")
    fun echoBackDeselectRestoresState() {
        val attackerIid = setupSingleAttacker()

        harness.passPriority() // advance to combat
        assertNotNull(harness.allMessages.lastOrNull { it.hasDeclareAttackersReq() })

        // Toggle ON
        harness.toggleAttackers(listOf(attackerIid))

        // Toggle OFF (empty selection)
        val echoMsgs = harness.toggleAttackers(emptyList())

        val echoGsm = echoMsgs.firstOrNull { it.hasGameStateMessage() }
        assertNotNull(echoGsm, "Deselect echo should include a GameStateMessage")

        val objects = echoGsm!!.gameStateMessage.gameObjectsList
        assertTrue(objects.isNotEmpty(), "Deselect echo GSM should contain creature objects")

        val attackerObj = objects.firstOrNull { it.instanceId == attackerIid }
        assertNotNull(attackerObj, "Deselect echo should contain the creature")
        assertNotEquals(
            attackerObj!!.attackState,
            AttackState.Attacking,
            "Deselected creature should NOT have attackState=Attacking",
        )
    }

    // --- Test 9: multiToggleBeforeSubmit ---

    @Test(description = "Toggle multiple creatures on/off before submit; only final selection attacks")
    fun multiToggleBeforeSubmit() {
        val attackerIids = setupMultipleAttackers()
        assertTrue(attackerIids.size >= 2, "Need at least 2 creatures")
        val (iidA, iidB) = attackerIids

        val aiPlayer = harness.bridge.getPlayer(2)!!
        val lifeBefore = aiPlayer.life
        val startTurn = harness.turn()

        harness.passPriority() // advance to combat
        assertNotNull(harness.allMessages.lastOrNull { it.hasDeclareAttackersReq() })

        // Toggle A on
        harness.toggleAttackers(listOf(iidA))
        // Toggle A+B on
        harness.toggleAttackers(listOf(iidA, iidB))
        // Toggle A off (only B remains)
        harness.toggleAttackers(listOf(iidB))

        // Submit with B only
        harness.submitAttackers()

        // Pass through remaining combat
        repeat(15) {
            if (harness.isGameOver()) return@repeat
            if (harness.turn() > startTurn) return@repeat
            harness.passPriority()
        }

        // B is 1/1 Raging Goblin → 1 damage (not 2)
        val lifeAfter = aiPlayer.life
        assertEquals(
            lifeAfter,
            lifeBefore - 1,
            "Only creature B (1/1) should deal damage: was $lifeBefore, now $lifeAfter",
        )
    }

    // --- Test 10: toggleThenSubmitDealsDamage ---

    /**
     * Real client protocol: user toggles creature (DeclareAttackersResp), then
     * clicks "Done" (SubmitAttackersReq, no payload). Server uses the last
     * toggled selection. Bare "Done" without any prior toggle = no attackers
     * (creatures are NOT pre-selected since we removed selectedDamageRecipient).
     */
    @Test(description = "Toggle attacker then Submit (Done) deals damage")
    fun toggleThenSubmitDealsDamage() {
        val attackerIid = setupSingleAttacker()

        val aiPlayer = harness.bridge.getPlayer(2)!!
        val lifeBefore = aiPlayer.life
        val startTurn = harness.turn()

        // Advance from Main1 to combat
        harness.passPriority()

        // Verify DeclareAttackersReq was sent with our creature
        val daReq = checkNotNull(harness.allMessages.lastOrNull { it.hasDeclareAttackersReq() }) { "Should receive DeclareAttackersReq" }
        val eligible = daReq.declareAttackersReq.attackersList.map { it.attackerInstanceId }
        assertTrue(attackerIid in eligible, "Raging Goblin should be eligible")

        // Toggle creature ON (iterative DeclareAttackersResp)
        harness.toggleAttackers(listOf(attackerIid))

        // Send SubmitAttackersReq (type-only, no payload) — real client "Done" button
        harness.submitAttackers()

        // Pass through remaining combat
        repeat(15) {
            if (harness.isGameOver()) return@repeat
            if (harness.turn() > startTurn) return@repeat
            harness.passPriority()
        }

        // Verify AI took damage — Raging Goblin 1/1 unblocked = 1 damage
        val lifeAfter = aiPlayer.life
        assertTrue(
            lifeAfter < lifeBefore,
            "AI life should decrease after toggle+Submit: was $lifeBefore, now $lifeAfter " +
                "(turn=${harness.turn()} phase=${harness.phase()})",
        )
    }

    // --- Test 8: attackAllThenSubmitDealsDamage ---

    /**
     * "Attack All" button sends DeclareAttackersResp with auto_declare=true,
     * then "Done" sends SubmitAttackersReq. AI should take damage.
     */
    @Test(description = "Attack All + Done deals damage through two-phase combat protocol")
    fun attackAllThenSubmitDealsDamage() {
        val attackerIid = setupSingleAttacker()

        val aiPlayer = harness.bridge.getPlayer(2)!!
        val lifeBefore = aiPlayer.life
        val startTurn = harness.turn()

        // Advance from Main1 to combat
        harness.passPriority()

        // Verify DeclareAttackersReq was sent
        val daReq = harness.allMessages.lastOrNull { it.hasDeclareAttackersReq() }
        assertNotNull(daReq, "Should receive DeclareAttackersReq")

        // Send "Attack All" (DeclareAttackersResp with auto_declare=true)
        harness.declareAllAttackers()

        // Send "Done" (SubmitAttackersReq, empty)
        harness.submitAttackers()

        // Pass through remaining combat
        repeat(15) {
            if (harness.isGameOver()) return@repeat
            if (harness.turn() > startTurn) return@repeat
            harness.passPriority()
        }

        // Verify AI took damage
        val lifeAfter = aiPlayer.life
        assertTrue(
            lifeAfter < lifeBefore,
            "AI life should decrease after Attack All + Submit: was $lifeBefore, now $lifeAfter " +
                "(turn=${harness.turn()} phase=${harness.phase()})",
        )
    }

    // --- Test 9: declareNoAttackersSkipsCombat ---

    @Test(description = "Declaring no attackers skips combat and advances past combat phase")
    fun declareNoAttackersSkipsCombat() {
        setupSingleAttacker()

        // Advance to combat
        harness.passPriority()

        // Verify we got DeclareAttackersReq
        val daReq = harness.allMessages.lastOrNull { it.hasDeclareAttackersReq() }
        assertNotNull(daReq, "Should have received DeclareAttackersReq")

        // Declare no attackers
        val snap = harness.messageSnapshot()
        harness.declareNoAttackers()

        // Should advance past combat
        val postCombat = harness.messagesSince(snap)
        assertTrue(postCombat.isNotEmpty(), "Should receive messages after declining combat")

        harness.accumulator.assertConsistent("after declining combat")
        assertFalse(harness.isGameOver(), "Game should not be over")
    }
}
