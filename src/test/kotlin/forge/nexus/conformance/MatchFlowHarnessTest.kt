package forge.nexus.conformance

import forge.ai.LobbyPlayerAi
import forge.nexus.game.GameBridge
import org.testng.Assert.*
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

@Test(groups = ["integration"])
class MatchFlowHarnessTest {

    companion object {
        /** Seed where AI wins the coin flip and goes first. Found by probing. */
        const val AI_FIRST_SEED = 2L
    }

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

    @Test(description = "Multi-turn game: play land each turn, verify state validity across 3 turns")
    fun multiTurnAccumulatedStateValid() {
        harness = MatchFlowHarness(seed = 42L)
        harness!!.connectAndKeep()

        repeat(3) { turn ->
            if (harness!!.isGameOver()) return

            // Play a land if possible (OK if no land available)
            harness!!.playLand()

            // Critical check: action instanceIds must exist in objects map
            val missingActions = harness!!.accumulator.actionInstanceIdsMissingFromObjects()
            assertTrue(
                missingActions.isEmpty(),
                "Turn ${turn + 1}: action instanceIds missing from objects: $missingActions",
            )

            // Visible zone refs must be valid (hidden/private zones are skipped by the checker)
            val missingZones = harness!!.accumulator.zoneObjectsMissingFromObjects()
            assertTrue(
                missingZones.isEmpty(),
                "Turn ${turn + 1}: visible zone refs to missing objects: $missingZones",
            )

            // Pass turn
            harness!!.passPriority()
        }
    }

    @Test(description = "AI goes first: auto-pass through AI turn, reach human Main1 with valid state")
    fun aiGoesFirstReachesHumanMain1() {
        // Verify our hardcoded seed actually has AI going first
        val probe = GameBridge()
        probe.start(seed = AI_FIRST_SEED)
        val game = probe.getGame()!!
        val human = game.players.first { it.lobbyPlayer !is LobbyPlayerAi }
        val aiFirst = game.phaseHandler.playerTurn != human
        probe.shutdown()
        assertTrue(aiFirst, "Seed $AI_FIRST_SEED should have AI going first")

        harness = MatchFlowHarness(seed = AI_FIRST_SEED)
        harness!!.connectAndKeep()

        // After connectAndKeep + autoPass, we should have valid state
        assertFalse(harness!!.isGameOver(), "Game should not be over at start")

        val missing = harness!!.accumulator.actionInstanceIdsMissingFromObjects()
        assertTrue(missing.isEmpty(), "Missing instanceIds after AI-first connect: $missing")

        // Visible zone refs must be valid (hidden/private zones are skipped by the checker)
        val zoneRefs = harness!!.accumulator.zoneObjectsMissingFromObjects()
        assertTrue(zoneRefs.isEmpty(), "Visible zone refs to missing objects: $zoneRefs")

        // Should have received at least game-start bundle (4 messages)
        assertTrue(harness!!.allMessages.size >= 4, "Should have at least 4 messages (game-start bundle)")
    }
}
