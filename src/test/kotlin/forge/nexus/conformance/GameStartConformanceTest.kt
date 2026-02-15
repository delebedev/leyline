package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

/**
 * Wire conformance: game-start bundle (post-keep).
 *
 * Real server sends 5 messages:
 *   1. GameStateMessage (Diff, SendHiFi, PhaseOrStepModified x2)
 *   2. GameStateMessage (Diff, SendHiFi, empty marker)
 *   3. GameStateMessage (Diff, SendAndRecord, PhaseOrStepModified)
 *   4. PromptReq (promptId=37)
 *   5. ActionsAvailableReq (promptId=2)
 *
 * EXPECTED TO FAIL: our BundleBuilder produces 4 messages (no PromptReq),
 * lacks PhaseOrStepModified annotations, and uses different updateType values.
 */
@Test(groups = ["integration", "conformance"])
class GameStartConformanceTest : ConformanceTestBase() {

    @Test(
        description = "Expected to fail: missing PromptReq, PhaseOrStepModified, updateType divergences",
        expectedExceptions = [AssertionError::class],
    )
    fun gameStartBundleMatchesGolden() {
        val (b, game, gsId) = startGameAtMain1()

        val result = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)
        val captured = fingerprint(result.messages)

        assertEquals(captured.size, 4, "Game start should produce 4 messages")
        assertConformance("game-start", captured)
    }

    @Test(enabled = false) // run manually to regenerate golden
    fun generateGoldenFromOurOutput() {
        val (b, game, gsId) = startGameAtMain1()
        val result = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)
        val captured = fingerprint(result.messages)
        saveGolden("game-start", captured)
        println("Generated game-start golden with ${captured.size} fingerprints:")
        println(formatFingerprints(captured))
    }
}
