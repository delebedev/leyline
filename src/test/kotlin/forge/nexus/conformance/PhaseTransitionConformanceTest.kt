package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

/**
 * Wire conformance: phase transition (double-diff pattern).
 *
 * Real server sends 2 Diff messages per phase change:
 *   1. GameStateMessage (Diff, SendHiFi, PhaseOrStepModified annotation, actions)
 *   2. GameStateMessage (Diff, SendHiFi, actions only, no annotations)
 *
 * EXPECTED TO FAIL: our BundleBuilder doesn't yet emit PhaseOrStepModified annotations
 * or include actions in phase transition diffs. The golden represents real server output.
 */
@Test(groups = ["integration", "conformance"])
class PhaseTransitionConformanceTest : ConformanceTestBase() {

    @Test(
        description = "Expected to fail: PhaseOrStepModified annotations and actions not yet implemented",
        expectedExceptions = [AssertionError::class],
    )
    fun phaseTransitionMatchesGolden() {
        val (b, game, gsId) = startGameAtMain1()

        val result = BundleBuilder.phaseTransitionDiff(game, b, "test-match", 1, 1, gsId)
        val captured = fingerprint(result.messages)

        assertEquals(captured.size, 2, "Phase transition should produce exactly 2 messages")
        assertConformance("phase-transition", captured)
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
