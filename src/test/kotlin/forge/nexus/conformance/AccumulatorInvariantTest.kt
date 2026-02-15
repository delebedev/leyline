package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.*
import org.testng.annotations.Test

@Test(groups = ["integration", "conformance"])
class AccumulatorInvariantTest : ConformanceTestBase() {

    @Test(description = "After game-start bundle, all action instanceIds exist in accumulated objects")
    fun gameStartActionIdsExistInObjects() {
        val (b, game, gsId) = startGameAtMain1()

        val result = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)

        val acc = ClientAccumulator()
        acc.processAll(result.messages)

        val missing = acc.actionInstanceIdsMissingFromObjects()
        assertTrue(missing.isEmpty(), "Action instanceIds missing from objects after game-start: $missing")
    }

    @Test(description = "After game-start, visible zone object references are valid")
    fun gameStartZoneRefsValid() {
        val (b, game, gsId) = startGameAtMain1()

        val result = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)

        val acc = ClientAccumulator()
        acc.processAll(result.messages)

        val missing = acc.zoneObjectsMissingFromObjects()
        assertTrue(missing.isEmpty(), "Zone references to missing objects: $missing")
    }

    @Test(description = "After game-start + play-land, all action instanceIds exist in accumulated objects")
    fun playLandActionIdsValid() {
        val (b, game, gsId) = startGameAtMain1()

        // Accumulate game-start
        val startResult = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)
        val acc = ClientAccumulator()
        acc.processAll(startResult.messages)

        // Play a land
        val action = playLand(b) ?: return
        b.snapshotState(game)

        // Accumulate post-action
        val postResult = BundleBuilder.postAction(game, b, "test-match", 1, startResult.nextMsgId, startResult.nextGsId)
        acc.processAll(postResult.messages)

        val missing = acc.actionInstanceIdsMissingFromObjects()
        assertTrue(missing.isEmpty(), "Action instanceIds missing after play-land: $missing")
    }

    @Test(description = "After game-start + cast-creature, all action instanceIds exist in accumulated objects")
    fun castCreatureActionIdsValid() {
        val (b, game, gsId) = startGameAtMain1()

        val startResult = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)
        val acc = ClientAccumulator()
        acc.processAll(startResult.messages)

        // Play land first (creature costs mana)
        playLand(b)
        b.snapshotState(game)
        val postLand = BundleBuilder.postAction(game, b, "test-match", 1, startResult.nextMsgId, startResult.nextGsId)
        acc.processAll(postLand.messages)

        // Cast creature
        val creature = castCreature(b) ?: return
        b.snapshotState(game)
        val postCast = BundleBuilder.postAction(game, b, "test-match", 1, postLand.nextMsgId, postLand.nextGsId)
        acc.processAll(postCast.messages)

        val missing = acc.actionInstanceIdsMissingFromObjects()
        assertTrue(missing.isEmpty(), "Action instanceIds missing after cast-creature: $missing")
    }

    @Test(description = "AI action diffs maintain valid accumulated state")
    fun aiActionDiffsKeepStateValid() {
        val (b, game, gsId) = startGameAtMain1()

        val acc = ClientAccumulator()

        // Game-start
        val startResult = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)
        acc.processAll(startResult.messages)
        b.snapshotState(game)

        // Simulate 3 AI action diffs
        var nextMsg = startResult.nextMsgId
        var nextGs = startResult.nextGsId
        repeat(3) {
            val aiResult = BundleBuilder.aiActionDiff(game, b, "test-match", 1, nextMsg, nextGs)
            acc.processAll(aiResult.messages)
            nextMsg = aiResult.nextMsgId
            nextGs = aiResult.nextGsId
            b.snapshotState(game)
        }

        // After accumulating AI diffs, action instanceIds should be valid
        val missingActions = acc.actionInstanceIdsMissingFromObjects()
        assertTrue(missingActions.isEmpty(), "Action instanceIds missing after AI diffs: $missingActions")
    }

    @Test(description = "game-start bundle has monotonically increasing gsId")
    fun gameStartGsIdMonotonic() {
        val (b, game, gsId) = startGameAtMain1()

        val result = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)

        val acc = ClientAccumulator()
        acc.processAll(result.messages)

        // Verify gsIds are strictly increasing
        for (i in 1 until acc.gsIdHistory.size) {
            assertTrue(
                acc.gsIdHistory[i] > acc.gsIdHistory[i - 1],
                "gsId should increase: ${acc.gsIdHistory[i - 1]} -> ${acc.gsIdHistory[i]} at index $i",
            )
        }
    }
}
