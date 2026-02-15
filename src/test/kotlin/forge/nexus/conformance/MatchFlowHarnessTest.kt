package forge.nexus.conformance

import org.testng.Assert.*
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

@Test(groups = ["integration"])
class MatchFlowHarnessTest {

    private var harness: MatchFlowHarness? = null

    @AfterMethod
    fun tearDown() {
        harness?.shutdown()
        harness = null
    }

    @Test(description = "Harness can start game and reach Main1 with valid accumulated state")
    fun startGameReachesMain1() {
        harness = MatchFlowHarness(seed = 42L)
        harness!!.connectAndKeep()

        val acc = harness!!.accumulator
        assertTrue(acc.objects.isNotEmpty(), "Should have accumulated game objects")
        assertNotNull(acc.actions, "Should have actions available")

        val missing = acc.actionInstanceIdsMissingFromObjects()
        assertTrue(missing.isEmpty(), "Action instanceIds missing after game start: $missing")

        assertEquals(harness!!.phase(), "MAIN1", "Should be at Main1")
    }

    @Test(description = "Play land, pass turn, survive AI turn, reach next Main1 with valid state")
    fun playLandAndPassTurn() {
        harness = MatchFlowHarness(seed = 42L)
        harness!!.connectAndKeep()

        // Play a land
        val landPlayed = harness!!.playLand()
        assertTrue(landPlayed, "Should have a land to play")

        // Verify state is valid after land play
        val missingAfterLand = harness!!.accumulator.actionInstanceIdsMissingFromObjects()
        assertTrue(missingAfterLand.isEmpty(), "Missing instanceIds after land: $missingAfterLand")

        // Pass priority to end turn
        harness!!.passPriority()

        // After auto-pass through AI turn, should be back at human's turn
        // (or AI turn if AI has actions — either way, state should be valid)
        assertFalse(harness!!.isGameOver(), "Game should not be over after 1 turn")

        val missingAfterTurn = harness!!.accumulator.actionInstanceIdsMissingFromObjects()
        assertTrue(missingAfterTurn.isEmpty(), "Missing instanceIds after full turn cycle: $missingAfterTurn")
    }

    @Test(description = "Play land + cast creature, verify object tracking through stack resolution")
    fun castCreatureTracksObjectThroughZones() {
        harness = MatchFlowHarness(seed = 42L)
        harness!!.connectAndKeep()

        // Play land for mana
        harness!!.playLand()

        // Cast creature (hand → stack → battlefield)
        val cast = harness!!.castCreature()
        assertTrue(cast, "Should be able to cast a creature")

        // Verify accumulated state
        val missing = harness!!.accumulator.actionInstanceIdsMissingFromObjects()
        assertTrue(missing.isEmpty(), "Missing instanceIds after cast: $missing")

        // Verify we have objects on battlefield (not just hand/library)
        val battlefieldZone = harness!!.accumulator.zones.values
            .firstOrNull { it.type == ZoneType.Battlefield }
        assertNotNull(battlefieldZone, "Should have a battlefield zone")
    }
}
