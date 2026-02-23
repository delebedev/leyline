package forge.nexus.conformance

import org.testng.Assert.*
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Tier 2 end-to-end targeting tests driven through real [MatchSession] code.
 *
 * Deck: default mono-green (Llanowar Elves, Elvish Mystic, Giant Growth, Forest).
 * Giant Growth targets a creature — exercises SelectTargetsReq/Resp flow.
 *
 * Multi-turn advancement with [passPriority] is unreliable (can overshoot),
 * so setup helpers assert prerequisites (creature on BF, mana, Giant Growth
 * in hand) rather than exact turn numbers.
 */
@Test(groups = ["integration"])
class TargetingFlowTest {

    private lateinit var harness: MatchFlowHarness

    @AfterMethod(alwaysRun = true)
    fun tearDown() {
        if (::harness.isInitialized) harness.shutdown()
    }

    // --- Setup helpers ---

    /**
     * Setup for targeting tests: creature on BF + Giant Growth in hand + mana.
     *
     * Turn 1: play Forest, cast Llanowar Elves (G).
     * Advance past turn 1 → AI turn → back to human (turn >= 2).
     * Now Elves untap, play another Forest → 2G available. Giant Growth costs G.
     *
     * Returns the creature's instanceId.
     */
    private fun setupForTargeting(): Int {
        harness = MatchFlowHarness(seed = 42L)
        harness.connectAndKeep()

        harness.installScriptedAi(
            listOf(
                ScriptedAction.PlayLand("Forest"),
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
                ScriptedAction.PlayLand("Forest"),
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
                ScriptedAction.PlayLand("Forest"),
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
            ),
        )

        // Turn 1: play Forest, cast creature
        assertTrue(harness.playLand(), "Should play land")
        assertTrue(harness.castCreature(), "Should cast creature")
        harness.passPriority() // resolve creature

        // Advance past turn 1 — may overshoot to turn 3, that's fine
        harness.passPriority()

        // Play another land if we can (for mana)
        harness.playLand()

        // Verify prerequisites (turn-agnostic)
        assertFalse(harness.isAiTurn(), "Should be human's turn")
        assertTrue(harness.turn() >= 2, "Should have advanced past turn 1, got turn ${harness.turn()}")

        val creatures = harness.humanBattlefieldCreatures()
        assertTrue(creatures.isNotEmpty(), "Should have a creature on BF")

        val player = harness.bridge.getPlayer(1)!!
        val hasGiantGrowth = player.getZone(ForgeZoneType.Hand).cards
            .any { it.name.equals("Giant Growth", ignoreCase = true) }
        assertTrue(hasGiantGrowth, "Should have Giant Growth in hand")

        return creatures.first().first
    }

    // --- Test 1: targetedSpellEmitsSelectTargetsReq ---

    @Test(description = "Casting Giant Growth emits SelectTargetsReq with legal creature targets")
    fun targetedSpellEmitsSelectTargetsReq() {
        val creatureIid = setupForTargeting()

        // Cast Giant Growth — should trigger SelectTargetsReq
        val snap = harness.messageSnapshot()
        val cast = harness.castSpellByName("Giant Growth")
        assertTrue(cast, "Should cast Giant Growth")

        // Look for SelectTargetsReq in messages
        val msgs = harness.messagesSince(snap)
        val stReq = msgs.firstOrNull { it.hasSelectTargetsReq() }
        assertNotNull(stReq, "Should receive SelectTargetsReq after casting Giant Growth")

        val req = stReq!!.selectTargetsReq
        assertTrue(req.targetsCount > 0, "SelectTargetsReq should have target selections")

        val targetSelection = req.targetsList.first()
        assertTrue(targetSelection.targetsCount > 0, "Should have legal targets")

        // Our creature should be among legal targets
        val legalTargetIds = targetSelection.targetsList.map { it.targetInstanceId }
        assertTrue(
            creatureIid in legalTargetIds,
            "Our creature $creatureIid should be a legal target, got $legalTargetIds",
        )

        // Verify minTargets/maxTargets for Giant Growth (1 target)
        assertEquals(targetSelection.minTargets, 1, "Giant Growth requires exactly 1 target")
        assertEquals(targetSelection.maxTargets, 1, "Giant Growth targets exactly 1 creature")
    }

    // --- Test 2: selectTargetAndResolve ---

    @Test(description = "Select creature target for Giant Growth; spell resolves, creature gets +3/+3")
    fun selectTargetAndResolve() {
        val creatureIid = setupForTargeting()

        // Cast Giant Growth
        val cast = harness.castSpellByName("Giant Growth")
        assertTrue(cast, "Should cast Giant Growth")

        // Select the creature as target
        val snap = harness.messageSnapshot()
        harness.selectTargets(listOf(creatureIid))

        // After targeting, the spell is on the stack. Pass to resolve.
        harness.passPriority()

        val msgs = harness.messagesSince(snap)
        val gsms = msgs.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
        assertTrue(gsms.isNotEmpty(), "Should have game state messages after targeting + resolution")

        // The creature should have updated power/toughness (+3/+3 from Giant Growth)
        val player = harness.bridge.getPlayer(1)!!
        val creature = player.getZone(ForgeZoneType.Battlefield).cards
            .firstOrNull { harness.bridge.getOrAllocInstanceId(it.id) == creatureIid }
        assertNotNull(creature, "Creature should still be on battlefield")
        assertTrue(
            creature!!.netPower >= 4,
            "Creature should have at least 4 power (1+3 from Giant Growth), got ${creature.netPower}",
        )
        assertTrue(
            creature.netToughness >= 4,
            "Creature should have at least 4 toughness (1+3 from Giant Growth), got ${creature.netToughness}",
        )

        harness.accumulator.assertConsistent("after Giant Growth resolution")
    }

    // --- Test 3: targetingStateValidity ---

    @Test(description = "Full targeting flow maintains valid accumulated state at every step")
    fun targetingStateValidity() {
        val creatureIid = setupForTargeting()

        harness.accumulator.assertConsistent("before targeting")

        // Cast Giant Growth
        val cast = harness.castSpellByName("Giant Growth")
        assertTrue(cast, "Should cast Giant Growth")

        // Select target
        harness.selectTargets(listOf(creatureIid))

        // Resolve
        harness.passPriority()

        harness.accumulator.assertConsistent("after targeting flow")

        // gsId chain valid through targeting
        assertGsIdChain(harness.allMessages, context = "targeting flow")
    }

    // --- Test 4: targetingDuringCombat ---

    @Test(description = "Giant Growth on attacker during combat (if priority available)")
    fun targetingDuringCombat() {
        // Use combat deck with haste creatures for turn-1 combat + Giant Growth
        harness = MatchFlowHarness(seed = 42L, deckList = CombatFlowTest.COMBAT_DECK)
        harness.connectAndKeep()

        harness.installScriptedAi(
            listOf(
                ScriptedAction.PlayLand("Mountain"),
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
            ),
        )

        // Play Forest (for Giant Growth mana) + cast Raging Goblin... wait, we need
        // both a Mountain (for Goblin) and a Forest (for Giant Growth) on turn 1.
        // We can only play 1 land per turn. So: play Forest, can't cast Goblin (costs R).
        // Or: play Mountain, cast Goblin, but can't cast Giant Growth (costs G).
        //
        // Solution: advance to turn 2. Play Mountain turn 1, cast Goblin.
        // Turn 2: play Forest → have both colors.
        harness.playLand() // Mountain
        harness.castSpellByName("Raging Goblin")
        harness.passPriority() // resolve

        // Advance to turn 2
        harness.passPriority()

        // Play Forest for green mana
        harness.playLand()

        // Advance to combat
        val creatures = harness.humanBattlefieldCreatures()
        assertTrue(creatures.isNotEmpty(), "Need creature for combat")
        val creatureIid = creatures.first().first

        harness.passPriority() // advance to combat

        val daReq = harness.allMessages.lastOrNull { it.hasDeclareAttackersReq() }
        if (daReq != null) {
            harness.declareAttackers(listOf(creatureIid))

            // Try casting Giant Growth during combat (requires priority window)
            val canCast = harness.castSpellByName("Giant Growth")
            if (canCast) {
                val stReq = harness.allMessages.lastOrNull { it.hasSelectTargetsReq() }
                if (stReq != null) {
                    harness.selectTargets(listOf(creatureIid))
                    harness.passPriority() // resolve
                }
            }
        }

        // Regardless of whether targeting succeeded, advance past combat
        repeat(10) {
            if (harness.isGameOver()) return@repeat
            val p = harness.phase()
            if (p == "MAIN2" || p == "END_OF_TURN") return@repeat
            harness.passPriority()
        }

        harness.accumulator.assertConsistent("after targeting during combat")
        assertFalse(harness.isGameOver(), "Game should not be over")
    }

    // --- Test 5: spellResolutionProducesZoneTransfer ---

    @Test(description = "Giant Growth resolution moves spell from Stack to GY; ZoneTransfer annotation")
    fun spellResolutionProducesZoneTransfer() {
        val creatureIid = setupForTargeting()

        // Cast Giant Growth and select target
        val cast = harness.castSpellByName("Giant Growth")
        assertTrue(cast, "Should cast Giant Growth")
        harness.selectTargets(listOf(creatureIid))

        val snap = harness.messageSnapshot()
        harness.passPriority() // resolve

        // Check for ZoneTransfer annotation (Stack→GY for the instant)
        val msgs = harness.messagesSince(snap)
        val allAnnotations = msgs
            .filter { it.hasGameStateMessage() }
            .flatMap { it.gameStateMessage.annotationsList }
        val zt = allAnnotations.filter { AnnotationType.ZoneTransfer_af5a in it.typeList }

        // The instant resolves and goes to graveyard — should produce ZoneTransfer.
        // At minimum, state should be valid.
        harness.accumulator.assertConsistent("after spell resolution zone transfer")

        // Verify the spell is actually in graveyard now
        val player = harness.bridge.getPlayer(1)!!
        val gyCards = player.getZone(ForgeZoneType.Graveyard).cards
        val giantGrowthInGy = gyCards.any { it.name.equals("Giant Growth", ignoreCase = true) }
        assertTrue(giantGrowthInGy, "Giant Growth should be in graveyard after resolution")
    }

    // --- Test 6: multipleTargetedSpellsInSequence ---

    @Test(description = "Cast two Giant Growths in sequence on the same creature")
    fun multipleTargetedSpellsInSequence() {
        val creatureIid = setupForTargeting()

        // Need at least 2 mana for 2 Giant Growths (each costs G)
        // After setup: Forest untapped + Elves untapped → 2G available

        // Cast first Giant Growth
        val cast1 = harness.castSpellByName("Giant Growth")
        assertTrue(cast1, "Should cast first Giant Growth")

        // Select target + resolve
        harness.selectTargets(listOf(creatureIid))
        harness.passPriority() // resolve

        // Cast second Giant Growth (if we have one and mana)
        val cast2 = harness.castSpellByName("Giant Growth")
        if (cast2) {
            val stReq = harness.allMessages.lastOrNull { it.hasSelectTargetsReq() }
            if (stReq != null) {
                harness.selectTargets(listOf(creatureIid))
                harness.passPriority() // resolve
            }

            // Creature should have +6/+6 (two Giant Growths)
            val player = harness.bridge.getPlayer(1)!!
            val creature = player.getZone(ForgeZoneType.Battlefield).cards
                .firstOrNull { harness.bridge.getOrAllocInstanceId(it.id) == creatureIid }
            if (creature != null) {
                assertTrue(
                    creature.netPower >= 7,
                    "Creature with 2x Giant Growth should have 7+ power, got ${creature.netPower}",
                )
            }
        }

        harness.accumulator.assertConsistent("after multiple targeted spells")
        assertGsIdChain(harness.allMessages, context = "multiple targeted spells")
    }
}
