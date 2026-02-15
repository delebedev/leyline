package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

/**
 * Wire conformance: phase transition (5-message pattern).
 *
 * Real server sends 5 messages per phase change with priority stop:
 *   1. GameStateMessage (Diff, SendHiFi, 2x PhaseOrStepModified, gameInfo)
 *   2. GameStateMessage (Diff, SendHiFi, echo — turnInfo + actions)
 *   3. GameStateMessage (Diff, SendAndRecord, 1x PhaseOrStepModified)
 *   4. PromptReq (promptId=37)
 *   5. ActionsAvailableReq (promptId=2)
 *
 * Uses shape-only comparison: checks message types, updateType, annotations,
 * and field presence but ignores deck-dependent action types.
 */
@Test(groups = ["integration", "conformance"])
class PhaseTransitionConformanceTest : ConformanceTestBase() {

    @Test(description = "Phase transition shape matches real server golden")
    fun phaseTransitionMatchesGolden() {
        val (b, game, gsId) = startGameAtMain1()

        val result = BundleBuilder.phaseTransitionDiff(game, b, "test-match", 1, 1, gsId)
        val captured = fingerprint(result.messages)

        assertEquals(captured.size, 5, "Phase transition should produce exactly 5 messages")
        assertShapeConformance("phase-transition", captured)
    }

    @Test(enabled = false) // run manually to regenerate golden
    fun generateGoldenFromOurOutput() {
        val (b, game, gsId) = startGameAtMain1()
        val result = BundleBuilder.phaseTransitionDiff(game, b, "test-match", 1, 1, gsId)
        val captured = fingerprint(result.messages)
        saveGolden("phase-transition", captured)
        println("Generated phase-transition golden with ${captured.size} fingerprints:")
        println(formatFingerprints(captured))
    }
}
