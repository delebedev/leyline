package forge.nexus.conformance

import forge.game.zone.ZoneType
import forge.web.game.GameBootstrap
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test

/**
 * Tests for [ScriptedPlayerController] — verifies the scripted AI
 * plays predetermined actions and falls back to passing on exhaustion.
 */
@Test(groups = ["integration"])
class ScriptedPlayerControllerTest {

    @BeforeClass(alwaysRun = true)
    fun initCardDatabase() {
        GameBootstrap.initializeCardDatabase(quiet = true)
        TestCardRegistry.ensureRegistered()
    }

    private var harness: MatchFlowHarness? = null

    @AfterMethod
    fun tearDown() {
        harness?.shutdown()
        harness = null
    }

    @Test
    fun scriptedAiPlaysForestOnTurn1() {
        // AI-first seed: AI goes first, gets priority on turn 1
        val h = MatchFlowHarness(seed = 2L, validating = false)
        harness = h
        h.connectAndKeep()

        h.installScriptedAi(
            listOf(
                ScriptedAction.PlayLand("Forest"),
                ScriptedAction.PassPriority,
                ScriptedAction.PassPriority,
                ScriptedAction.PassPriority,
                ScriptedAction.PassPriority,
                ScriptedAction.PassPriority,
                ScriptedAction.PassPriority,
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
                ScriptedAction.PassPriority,
            ),
        )

        // Pass through AI turn + our turn until turn 2
        h.passUntilTurn(2, maxPasses = 30)

        // After AI's turn 1, it should have played a Forest onto the battlefield
        val aiPlayer = h.bridge.getPlayer(2)!!
        val aiBf = aiPlayer.getZone(ZoneType.Battlefield)
        val forests = aiBf.cards.filter { it.name == "Forest" }
        assertTrue(forests.isNotEmpty(), "AI should have played at least one Forest, BF=${aiBf.cards.map { it.name }}")
    }

    @Test
    fun scriptExhaustionDoesNotHang() {
        // Empty script — AI should just pass on every decision
        val h = MatchFlowHarness(seed = 2L, validating = false)
        harness = h
        h.connectAndKeep()

        h.installScriptedAi(emptyList())

        // This should not hang — exhausted script falls back to pass
        h.passUntilTurn(2, maxPasses = 30)
        assertFalse(h.isGameOver(), "Game should not be over after 1 turn with passing AI")
    }

    @Test
    fun illegalActionInScriptDoesNotHang() {
        // Script tries to play a card that doesn't exist — should warn and pass
        val h = MatchFlowHarness(seed = 2L, validating = false)
        harness = h
        h.connectAndKeep()

        h.installScriptedAi(
            listOf(
                ScriptedAction.PlayLand("Nonexistent Card"),
                ScriptedAction.PassPriority,
                ScriptedAction.PassPriority,
                ScriptedAction.PassPriority,
                ScriptedAction.PassPriority,
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
                ScriptedAction.PassPriority,
            ),
        )

        // Should not hang even with an illegal action
        h.passUntilTurn(2, maxPasses = 30)
        assertFalse(h.isGameOver(), "Game should not be over despite illegal scripted action")
    }
}
