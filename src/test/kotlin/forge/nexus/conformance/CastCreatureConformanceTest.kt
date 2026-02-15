package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * Wire conformance: cast creature spell (player perspective).
 *
 * Expected sequence:
 *   Bundle 1 (spell on stack):
 *     1. GameStateMessage (Diff, CastSpell annotations: ObjectIdChanged + UserActionTaken
 *        + ManaPaid + TappedUntappedPermanent + AbilityInstanceCreated + ZoneTransfer)
 *     2. ActionsAvailableReq (prompt id=2, Pass available)
 *   Bundle 2 (after pass — resolution):
 *     3. GameStateMessage (Diff, ResolutionStart + ZoneTransfer/Resolve + ResolutionComplete)
 *     4. ActionsAvailableReq (prompt id=2)
 *
 * Uses shape-only comparison against our own golden (player perspective).
 * The real server golden in full-game recordings shows opponent perspective
 * (4x GameStateMessage without ActionsAvailableReq).
 */
@Test(groups = ["integration", "conformance"])
class CastCreatureConformanceTest : ConformanceTestBase() {

    @Test(description = "Cast creature shape matches golden")
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
        assertShapeConformance("cast-creature", captured)
    }

    @Test(enabled = false) // run manually to regenerate golden
    fun generateGoldenFromOurOutput() {
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
        saveGolden("cast-creature", captured)
        println("Current cast-creature output (${captured.size} fingerprints):")
        println(formatFingerprints(captured))
    }
}
