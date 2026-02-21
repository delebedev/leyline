package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.*
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Validates gsId chain integrity across message bundles.
 *
 * Client validates `prevGameStateId == currentGsId` before merging a Diff.
 * Mismatch = silently dropped. These tests catch chain breaks that cause
 * invisible AI actions or dropped state updates.
 */
@Test(groups = ["integration", "conformance"])
class GsIdChainTest : ConformanceTestBase() {

    // --- AI diff chain ---

    @Test(description = "aiActionDiff produces single GSM with no pendingMessageCount")
    fun aiDiffNoPendingMessageCount() {
        val (b, game, gsId) = startGameAtMain1()
        b.snapshotState(game, gsId)

        val result = BundleBuilder.aiActionDiff(game, b, TEST_MATCH_ID, SEAT_ID, 1, gsId)
        assertEquals(result.messages.size, 1, "aiActionDiff should produce 1 GSM")

        val gsm = result.messages[0].gameStateMessage
        assertEquals(
            gsm.pendingMessageCount,
            0,
            "AI action diff must NOT have pendingMessageCount (no follow-up expected)",
        )
    }

    @Test(description = "Chained aiActionDiff calls maintain gsId continuity")
    fun chainedAiDiffsGsIdContinuity() {
        val (b, game, gsId) = startGameAtMain1()
        b.snapshotState(game, gsId)

        val allGsms = mutableListOf<GameStateMessage>()
        var nextMsg = 1
        var nextGs = gsId

        repeat(3) {
            val result = BundleBuilder.aiActionDiff(game, b, TEST_MATCH_ID, SEAT_ID, nextMsg, nextGs)
            result.messages.forEach { allGsms.add(it.gameStateMessage) }
            nextMsg = result.nextMsgId
            nextGs = result.nextGsId
            b.snapshotState(game, nextGs)
        }

        // Every GSM should have strictly increasing gsId
        for (i in 1 until allGsms.size) {
            val prev = allGsms[i - 1]
            val curr = allGsms[i]
            assertTrue(
                curr.gameStateId > prev.gameStateId,
                "gsId[$i] (${curr.gameStateId}) should be > gsId[${i - 1}] (${prev.gameStateId})",
            )
        }
    }

    // --- Player-side chain ---

    @Test(description = "postAction GSM has prevGameStateId referencing prior gsId")
    fun postActionPrevGsIdPresent() {
        val (b, game, gsId) = startGameAtMain1()

        playLand(b) ?: error("playLand failed at seed 42")
        val result = postAction(game, b, 1, gsId)
        val gsm = result.gsmOrNull ?: error("No GSM in bundle result")

        assertEquals(
            gsm.prevGameStateId,
            gsId,
            "postAction prevGameStateId should reference the prior gsId passed in",
        )
    }

    @Test(description = "postAction GSM has pendingMessageCount=1 (AAR follows)")
    fun postActionHasPendingForAar() {
        val (b, game, gsId) = startGameAtMain1()

        playLand(b) ?: error("playLand failed at seed 42")
        val result = postAction(game, b, 1, gsId)
        val gsm = result.gsmOrNull ?: error("No GSM in bundle result")

        assertEquals(
            gsm.pendingMessageCount,
            1,
            "postAction GSM should have pendingMessageCount=1 (AAR follows)",
        )
    }

    @Test(description = "Chained postAction calls maintain gsId monotonicity")
    fun chainedPostActionsGsIdMonotonic() {
        val (b, game, gsId) = startGameAtMain1()

        playLand(b) ?: error("playLand failed at seed 42")
        val result1 = postAction(game, b, 1, gsId)
        b.snapshotState(game)

        castCreature(b) ?: error("castCreature failed at seed 42")
        val result2 = postAction(game, b, result1.nextMsgId, result1.nextGsId)

        assertTrue(
            result2.nextGsId > result1.nextGsId,
            "Second postAction gsId (${result2.nextGsId}) should be > first (${result1.nextGsId})",
        )
    }

    // --- Phase transition chain ---

    @Test(description = "phaseTransitionDiff echo has prevGameStateId and no pendingMessageCount")
    fun phaseTransitionEchoFields() {
        val (b, game, gsId) = startGameAtMain1()

        val result = BundleBuilder.phaseTransitionDiff(game, b, TEST_MATCH_ID, SEAT_ID, 1, gsId)
        assertEquals(result.messages.size, 5, "phaseTransitionDiff should produce 5 messages")

        val gsms = result.messages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
        assertTrue(gsms.size >= 3, "Should have at least 3 GSMs in phase transition")

        // msg2 (echo) — index 1
        val msg1 = gsms[0]
        val echo = gsms[1]

        assertEquals(
            echo.prevGameStateId,
            msg1.gameStateId,
            "Phase transition echo prevGameStateId should chain from msg1",
        )
        assertEquals(
            echo.pendingMessageCount,
            0,
            "Phase transition echo must NOT have pendingMessageCount",
        )

        // msg3 (commit) — should chain from msg2
        val commit = gsms[2]
        assertEquals(
            commit.prevGameStateId,
            echo.gameStateId,
            "Phase transition commit prevGameStateId should chain from echo",
        )
    }

    // --- gameStart chain ---

    @Test(description = "gameStart bundle gsIds are strictly monotonic")
    fun gameStartGsIdsMonotonic() {
        val (b, game, gsId) = startGameAtMain1()

        val result = gameStart(game, b, 1, gsId)
        val gsIds = result.messages
            .filter { it.hasGameStateMessage() }
            .map { it.gameStateMessage.gameStateId }

        for (i in 1 until gsIds.size) {
            assertTrue(
                gsIds[i] > gsIds[i - 1],
                "gameStart gsIds should be monotonic: ${gsIds[i - 1]} -> ${gsIds[i]}",
            )
        }
    }

    @Test(description = "gameStart → postAction gsId chain is continuous")
    fun gameStartToPostActionChain() {
        val (b, game, gsId) = startGameAtMain1()

        val startResult = gameStart(game, b, 1, gsId)
        b.snapshotState(game, startResult.nextGsId)

        playLand(b) ?: error("playLand failed at seed 42")
        val postResult = postAction(game, b, startResult.nextMsgId, startResult.nextGsId)
        val gsm = postResult.gsmOrNull ?: error("No GSM in post-action result")

        assertEquals(
            gsm.prevGameStateId,
            startResult.nextGsId,
            "postAction after gameStart should chain prevGameStateId from gameStart's last gsId",
        )
    }
}
