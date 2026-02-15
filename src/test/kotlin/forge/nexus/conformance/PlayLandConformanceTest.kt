package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * Wire conformance: play-land action.
 *
 * Real server sends 2 messages per land play:
 *   1. GameStateMessage (Diff, SendAndRecord, ObjectIdChanged + UserActionTaken + ZoneTransfer/PlayLand)
 *   2. ActionsAvailableReq (prompt id=2, includes Pass)
 *
 * Uses shape-only comparison: checks message types, updateType, annotations,
 * and field presence but ignores deck-dependent action types.
 */
@Test(groups = ["integration", "conformance"])
class PlayLandConformanceTest : ConformanceTestBase() {

    @Test(description = "Play-land shape matches real server golden")
    fun playLandMatchesGolden() {
        val (b, game, gsId) = startGameAtMain1()

        // Play a land
        val action = playLand(b) ?: return // no land in hand -- skip

        // Capture the postAction bundle
        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)
        val captured = fingerprint(result.messages)

        assertTrue(captured.isNotEmpty(), "Should have captured GRE messages")

        // Compare against golden
        assertShapeConformance("play-land", captured)
    }

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
