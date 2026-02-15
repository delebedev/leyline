package forge.nexus.conformance

import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * Wire conformance: new turn.
 *
 * Arena sends 2 messages per new turn:
 *   1. GameStateMessage (Diff, SendHiFi, NewTurnStarted + PhaseOrStepModified x4)
 *   2. GameStateMessage (Diff, SendHiFi, echo — no annotations)
 *
 * The 4 PhaseOrStepModified annotations represent the untap -> upkeep -> draw -> main1
 * phase transitions that happen when a new turn begins.
 */
@Test(groups = ["integration", "conformance"])
class NewTurnConformanceTest : ConformanceTestBase() {

    @Test(description = "New turn shape: 2 msgs with NewTurnStarted annotation")
    fun arenaNewTurnStructure() {
        val golden = loadGolden("arena-new-turn")

        // 2 messages: diff + echo
        assertEquals(golden.size, 2, "New turn should have 2 messages")

        // First: new turn diff
        assertEquals(golden[0].greMessageType, "GameStateMessage")
        assertEquals(golden[0].gsType, "Diff")
        assertEquals(golden[0].updateType, "SendHiFi")
        assertTrue(golden[0].annotationTypes.contains("NewTurnStarted"), "Should have NewTurnStarted")

        // Should have multiple PhaseOrStepModified (untap -> upkeep -> draw -> main1 transitions)
        val phaseCount = golden[0].annotationTypes.count { it == "PhaseOrStepModified" }
        assertTrue(phaseCount >= 2, "Should have multiple PhaseOrStepModified, got $phaseCount")
        assertEquals(phaseCount, 4, "Arena recording has exactly 4 PhaseOrStepModified")

        // players field present (turn ownership changes)
        assertTrue(golden[0].fieldPresence.contains("players"), "Diff should include players")
        assertTrue(golden[0].fieldPresence.contains("annotations"), "Diff should include annotations")

        // No annotation categories (NewTurnStarted doesn't use category detail)
        assertTrue(golden[0].annotationCategories.isEmpty(), "NewTurnStarted has no category")

        // Echo: same envelope, no annotations
        assertEquals(golden[1].greMessageType, "GameStateMessage")
        assertEquals(golden[1].gsType, "Diff")
        assertEquals(golden[1].updateType, "SendHiFi")
        assertTrue(golden[1].annotationTypes.isEmpty(), "Echo should have no annotation types")
        assertTrue(golden[1].annotationCategories.isEmpty(), "Echo should have no annotation categories")
    }
}
