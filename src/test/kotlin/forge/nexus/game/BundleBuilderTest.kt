package forge.nexus.game

import forge.nexus.conformance.ConformanceTestBase
import forge.nexus.game.mapper.PromptIds
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages

/**
 * Tests for [BundleBuilder] proto assembly.
 *
 * Unit group: pure proto wrappers (no game needed).
 * Conformance group: bundle shape checks via [startWithBoard].
 */
@Test(groups = ["conformance"])
class BundleBuilderTest : ConformanceTestBase() {

    /** queuedGameState wraps a GameStateMessage with type 51. */
    @Test(groups = ["unit"])
    fun queuedGameStateShape() {
        val gs = Messages.GameStateMessage.newBuilder()
            .setType(Messages.GameStateType.Full)
            .setGameStateId(42)
            .build()

        val msg = BundleBuilder.queuedGameState(gs, 2, MessageCounter(initialGsId = 42, initialMsgId = 9))

        assertEquals(
            msg.type,
            Messages.GREMessageType.QueuedGameStateMessage,
            "Should be QueuedGameStateMessage type",
        )
        assertTrue(msg.hasGameStateMessage(), "Should contain game state")
        assertEquals(msg.gameStateMessage.gameStateId, 42)
    }

    /** declareAttackersBundle has correct GRE message types. */
    @Test
    fun declareAttackersBundleShape() {
        val (b, game, counter) = startWithBoard { _, _, _ -> }

        val result = BundleBuilder.declareAttackersBundle(game, b, TEST_MATCH_ID, 1, counter)

        assertEquals(result.messages.size, 2, "Attackers bundle should have 2 messages")
        assertEquals(
            result.messages[0].type,
            Messages.GREMessageType.GameStateMessage_695e,
            "First message should be GameStateMessage",
        )
        assertEquals(
            result.messages[1].type,
            Messages.GREMessageType.DeclareAttackersReq_695e,
            "Second message should be DeclareAttackersReq",
        )
        assertEquals(
            result.messages[1].prompt.promptId,
            6,
            "DeclareAttackersReq should have prompt id=6",
        )
    }

    /** declareBlockersBundle has correct GRE message types. */
    @Test
    fun declareBlockersBundleShape() {
        val (b, game, counter) = startWithBoard { _, _, _ -> }

        val result = BundleBuilder.declareBlockersBundle(game, b, TEST_MATCH_ID, 1, counter)

        assertEquals(result.messages.size, 2, "Blockers bundle should have 2 messages")
        assertEquals(
            result.messages[0].type,
            Messages.GREMessageType.GameStateMessage_695e,
            "First message should be GameStateMessage",
        )
        assertEquals(
            result.messages[1].type,
            Messages.GREMessageType.DeclareBlockersReq_695e,
            "Second message should be DeclareBlockersReq",
        )
        assertEquals(
            result.messages[1].prompt.promptId,
            7,
            "DeclareBlockersReq should have prompt id=7",
        )
    }

    /** selectTargetsBundle has correct GRE message types and prompt id. */
    @Test
    fun selectTargetsBundleShape() {
        val (b, game, counter) = startWithBoard { _, _, _ -> }

        val candidateRefs = listOf(
            forge.web.dto.PromptCandidateRefDto(0, "card", 999, "Battlefield"),
        )
        val prompt = forge.web.game.InteractivePromptBridge.PendingPrompt(
            promptId = "test-prompt",
            request = forge.web.game.PromptRequest(
                promptType = "choose_cards",
                message = "Choose target",
                options = listOf("Target A"),
                min = 1,
                max = 1,
                candidateRefs = candidateRefs,
            ),
            future = java.util.concurrent.CompletableFuture(),
        )
        val result = BundleBuilder.selectTargetsBundle(game, b, TEST_MATCH_ID, 1, counter, prompt)

        assertEquals(result.messages.size, 2, "Targets bundle should have 2 messages")
        assertEquals(
            result.messages[0].type,
            Messages.GREMessageType.GameStateMessage_695e,
        )
        assertEquals(
            result.messages[1].type,
            Messages.GREMessageType.SelectTargetsReq_695e,
        )
        assertEquals(result.messages[1].prompt.promptId, PromptIds.SELECT_TARGETS)

        val wrapper = result.messages[1]
        assertEquals(wrapper.allowCancel, Messages.AllowCancel.Abort, "Should have allowCancel=Abort")
        assertTrue(wrapper.allowUndo, "Should have allowUndo=true")
    }
}
