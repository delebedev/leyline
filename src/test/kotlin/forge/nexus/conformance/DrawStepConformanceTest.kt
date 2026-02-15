package forge.nexus.conformance

import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * Wire conformance: draw step.
 *
 * Arena sends 2 messages per draw step:
 *   1. GameStateMessage (Diff, SendHiFi, ObjectIdChanged + PhaseOrStepModified + ZoneTransfer, category=Draw)
 *   2. GameStateMessage (Diff, SendHiFi, echo — no annotations)
 *
 * The draw step is the aiActionDiff pattern applied when a card moves from library to hand
 * at turn start. This test validates the golden structure extracted from a real Arena recording.
 */
@Test(groups = ["integration", "conformance"])
class DrawStepConformanceTest : ConformanceTestBase() {

    @Test(description = "Draw step shape: 2 msgs with Draw category annotations")
    fun arenaDrawStepStructure() {
        val golden = loadGolden("arena-draw-step")

        // 2 messages: diff + echo
        assertEquals(golden.size, 2, "Draw step should have 2 messages")

        // First: zone transfer diff with Draw category
        assertEquals(golden[0].greMessageType, "GameStateMessage")
        assertEquals(golden[0].gsType, "Diff")
        assertEquals(golden[0].updateType, "SendHiFi")
        assertTrue(golden[0].annotationTypes.contains("ZoneTransfer"), "Should have ZoneTransfer")
        assertTrue(golden[0].annotationTypes.contains("ObjectIdChanged"), "Should have ObjectIdChanged")
        assertTrue(golden[0].annotationTypes.contains("PhaseOrStepModified"), "Should have PhaseOrStepModified")
        assertTrue(golden[0].annotationCategories.contains("Draw"), "Should have Draw category")

        // zones + objects present (card moved from library to hand)
        assertTrue(golden[0].fieldPresence.contains("zones"), "Diff should include zones")
        assertTrue(golden[0].fieldPresence.contains("objects"), "Diff should include objects")
        assertTrue(golden[0].fieldPresence.contains("annotations"), "Diff should include annotations")

        // Echo: same envelope, no annotations
        assertEquals(golden[1].greMessageType, "GameStateMessage")
        assertEquals(golden[1].gsType, "Diff")
        assertEquals(golden[1].updateType, "SendHiFi")
        assertTrue(golden[1].annotationTypes.isEmpty(), "Echo should have no annotation types")
        assertTrue(golden[1].annotationCategories.isEmpty(), "Echo should have no annotation categories")
    }
}
