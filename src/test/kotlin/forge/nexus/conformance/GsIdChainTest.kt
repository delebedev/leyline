package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import forge.nexus.game.snapshotFromGame
import org.testng.Assert.*
import org.testng.annotations.Test

/**
 * Validates gsId chain **semantics** that go beyond structural invariants.
 *
 * Structural checks (gsId monotonicity, prevGsId validity, msgId monotonicity,
 * no self-referential gsIds) are now automatic via [ValidatingMessageSink] and
 * run on every message in every conformance test.
 *
 * What remains here: scenario-specific contracts about pendingMessageCount values,
 * phase transition bundle structure, and cross-bundle chain continuity.
 */
@Test(groups = ["integration", "conformance"])
class GsIdChainTest : ConformanceTestBase() {

    @Test(description = "aiActionDiff produces single GSM with no pendingMessageCount")
    fun aiDiffNoPendingMessageCount() {
        val (b, game, counter) = startGameAtMain1()
        b.snapshotFromGame(game, counter.currentGsId())

        val result = BundleBuilder.aiActionDiff(game, b, TEST_MATCH_ID, SEAT_ID, counter)
        assertEquals(result.messages.size, 1, "aiActionDiff should produce 1 GSM")

        val gsm = result.messages[0].gameStateMessage
        assertEquals(
            gsm.pendingMessageCount,
            0,
            "AI action diff must NOT have pendingMessageCount (no follow-up expected)",
        )
    }

    @Test(description = "postAction GSM has pendingMessageCount=1 (AAR follows)")
    fun postActionHasPendingForAar() {
        val (b, game, counter) = startGameAtMain1()

        playLand(b) ?: error("playLand failed at seed 42")
        val result = postAction(game, b, counter)
        val gsm = result.gsmOrNull ?: error("No GSM in bundle result")

        assertEquals(
            gsm.pendingMessageCount,
            1,
            "postAction GSM should have pendingMessageCount=1 (AAR follows)",
        )
    }

    @Test(description = "phaseTransitionDiff produces 5 messages with correct echo chain")
    fun phaseTransitionEchoFields() {
        val (b, game, counter) = startGameAtMain1()

        val result = BundleBuilder.phaseTransitionDiff(game, b, TEST_MATCH_ID, SEAT_ID, counter)
        assertEquals(result.messages.size, 5, "phaseTransitionDiff should produce 5 messages")

        val gsms = result.messages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
        assertTrue(gsms.size >= 3, "Should have at least 3 GSMs in phase transition")

        // msg2 (echo) should chain from msg1 and have no pendingMessageCount
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

        // msg3 (commit) should chain from echo
        val commit = gsms[2]
        assertEquals(
            commit.prevGameStateId,
            echo.gameStateId,
            "Phase transition commit prevGameStateId should chain from echo",
        )
    }
}
