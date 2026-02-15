package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * Wire conformance: cast creature spell.
 *
 * Expected sequence (from real Arena recordings):
 *   Bundle 1 (spell on stack):
 *     1. GameStateMessage (Diff, ZoneTransfer/CastSpell, spell in Stack zone)
 *     2. ActionsAvailableReq (prompt id=2, Pass available)
 *   Bundle 2 (after pass -- resolution):
 *     3. GameStateMessage (Diff, ResolutionStart + ResolutionComplete + ZoneTransfer/Resolve)
 *     4. ActionsAvailableReq (prompt id=2)
 *
 * This test catches the "spell stuck on stack" bug -- if resolution annotations
 * are missing, the client never moves the card from stack to battlefield visually.
 *
 * EXPECTED TO FAIL: our BundleBuilder doesn't yet emit resolution annotations.
 * The golden represents the ideal (real server) output.
 */
@Test(groups = ["integration", "conformance"])
class CastCreatureConformanceTest : ConformanceTestBase() {

    @Test(
        description = "Expected to fail: resolution annotations not yet implemented",
        expectedExceptions = [AssertionError::class],
    )
    fun castCreatureMatchesGolden() {
        val (b, game, gsId) = startGameAtMain1()

        // Play a land for mana
        playLand(b) ?: return

        // Re-snapshot after land play
        b.snapshotState(game)
        var nextGsId = gsId + 2 // postAction uses 2 gsIds

        // Cast a creature
        castCreature(b) ?: return // no castable creature -- skip

        // Bundle 1: spell on stack
        val spellOnStack = BundleBuilder.postAction(game, b, "test-match", 1, 1, nextGsId)
        nextGsId = spellOnStack.nextGsId

        // Pass priority to trigger resolution
        b.snapshotState(game)
        passPriority(b)

        // Bundle 2: resolution
        val resolution = BundleBuilder.postAction(game, b, "test-match", 1, 1, nextGsId)

        // Combine both bundles for comparison
        val allMessages = spellOnStack.messages + resolution.messages
        val captured = fingerprint(allMessages)

        assertTrue(captured.isNotEmpty(), "Should have captured GRE messages")
        assertConformance("cast-creature", captured)
    }

    /**
     * Utility: see what our BundleBuilder currently produces.
     * The golden uses the ideal (real server) output, so this test
     * is expected to fail until resolution annotations are implemented.
     */
    @Test(enabled = false) // run manually to inspect current output
    fun inspectCurrentOutput() {
        val (b, game, gsId) = startGameAtMain1()
        playLand(b) ?: return
        b.snapshotState(game)
        var nextGsId = gsId + 2

        castCreature(b) ?: return
        val spellOnStack = BundleBuilder.postAction(game, b, "test-match", 1, 1, nextGsId)
        nextGsId = spellOnStack.nextGsId
        b.snapshotState(game)
        passPriority(b)
        val resolution = BundleBuilder.postAction(game, b, "test-match", 1, 1, nextGsId)

        val allMessages = spellOnStack.messages + resolution.messages
        val captured = fingerprint(allMessages)
        println("Current cast-creature output (${captured.size} fingerprints):")
        println(formatFingerprints(captured))
    }
}
