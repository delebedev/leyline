package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

/**
 * Wire conformance: phase transition (double-diff pattern).
 *
 * Every phase change sends exactly 2 Diff messages:
 *   1. GameStateMessage (Diff, phase/step change, SendHiFi or SendAndRecord)
 *   2. GameStateMessage (Diff, empty priority-pass marker)
 */
@Test(groups = ["integration", "conformance"])
class PhaseTransitionConformanceTest : ConformanceTestBase() {

    @Test
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
