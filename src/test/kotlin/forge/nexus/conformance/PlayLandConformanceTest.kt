package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * Wire conformance: play-land action.
 *
 * Verifies that playing a land produces the correct GRE message sequence:
 *   1. GameStateMessage (Diff, SendAndRecord, ZoneTransfer/PlayLand annotation)
 *   2. ActionsAvailableReq (prompt id=2, includes Pass)
 *
 * EXPECTED TO FAIL: our BundleBuilder doesn't yet emit ZoneTransfer/PlayLand annotations.
 * The golden represents the ideal (real server) output.
 */
@Test(groups = ["integration", "conformance"])
class PlayLandConformanceTest : ConformanceTestBase() {

    @Test(
        description = "Expected to fail: PlayLand annotations not yet implemented",
        expectedExceptions = [AssertionError::class],
    )
    fun playLandMatchesGolden() {
        val (b, game, gsId) = startGameAtMain1()

        // Play a land
        val action = playLand(b) ?: return // no land in hand -- skip

        // Capture the postAction bundle
        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)
        val captured = fingerprint(result.messages)

        assertTrue(captured.isNotEmpty(), "Should have captured GRE messages")

        // Compare against golden
        assertConformance("play-land", captured)
    }

    /**
     * Utility: generate the golden file from our current output.
     * Run manually once, then commit the golden file.
     */
    @Test(enabled = false) // run manually to regenerate golden
    fun generateGoldenFromOurOutput() {
        val (b, game, gsId) = startGameAtMain1()
        playLand(b) ?: return
        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)
        val captured = fingerprint(result.messages)
        saveGolden("play-land", captured)
        println("Generated play-land golden with ${captured.size} fingerprints:")
        println(formatFingerprints(captured))
    }
}
