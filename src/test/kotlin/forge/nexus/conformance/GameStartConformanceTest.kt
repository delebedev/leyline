package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

/**
 * Wire conformance: game-start bundle (post-keep).
 *
 * Expected 4-message sequence:
 *   1. GameStateMessage (Diff, Beginning/Upkeep, SendHiFi)
 *   2. GameStateMessage (Diff, empty priority-pass marker)
 *   3. GameStateMessage (Full, Main1, SendAndRecord, all zones + objects)
 *   4. ActionsAvailableReq (prompt id=2)
 */
@Test(groups = ["integration", "conformance"])
class GameStartConformanceTest : ConformanceTestBase() {

    @Test
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
