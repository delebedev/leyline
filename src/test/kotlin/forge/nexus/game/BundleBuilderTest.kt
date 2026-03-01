package forge.nexus.game

import forge.game.zone.ZoneType
import forge.nexus.conformance.ConformanceTestBase
import forge.nexus.game.mapper.PromptIds
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

/**
 * Tests for [BundleBuilder] proto assembly.
 *
 * Unit group: pure proto wrappers (no game needed).
 * Conformance group: bundle shape checks via [startWithBoard].
 */
@Test(groups = ["conformance"])
class BundleBuilderTest : ConformanceTestBase() {

    // --- Unit tests (pure proto, no game) ---

    /** queuedGameState wraps a GameStateMessage with type 51. */
    @Test(groups = ["unit"])
    fun queuedGameStateShape() {
        val gs = Messages.GameStateMessage.newBuilder()
            .setType(GameStateType.Full)
            .setGameStateId(42)
            .build()

        val msg = BundleBuilder.queuedGameState(gs, 2, MessageCounter(initialGsId = 42, initialMsgId = 9))

        assertEquals(msg.type, GREMessageType.QueuedGameStateMessage)
        assertTrue(msg.hasGameStateMessage(), "Should contain game state")
        assertEquals(msg.gameStateMessage.gameStateId, 42)
    }

    /** edictalPass sends a server-forced Pass action for the given seat. */
    @Test(groups = ["unit"])
    fun edictalPassShape() {
        val counter = MessageCounter(initialGsId = 10, initialMsgId = 0)
        val result = BundleBuilder.edictalPass(seatId = 1, counter = counter)

        assertEquals(result.messages.size, 1, "Should produce 1 message")
        val msg = result.messages[0]
        assertEquals(msg.type, GREMessageType.EdictalMessage_695e)
        assertTrue(msg.hasEdictalMessage(), "Should contain edictal message")

        val inner = msg.edictalMessage.edictMessage
        assertEquals(inner.type, Messages.ClientMessageType.PerformActionResp_097b)
        assertEquals(inner.systemSeatId, 1)
        val action = inner.performActionResp.actionsList.first()
        assertEquals(action.actionType, Messages.ActionType.Pass)
    }

    /** gameOverBundle produces 3 GSM diffs + IntermissionReq. */
    @Test(groups = ["unit"])
    fun gameOverBundleShape() {
        val counter = MessageCounter(initialGsId = 10, initialMsgId = 0)
        val result = BundleBuilder.gameOverBundle(
            winningTeam = 1,
            matchId = "test-match",
            seatId = 1,
            counter = counter,
            losingPlayerSeatId = 2,
            lossReason = 0,
        )

        assertEquals(result.messages.size, 4, "Should produce 3 GSM + 1 IntermissionReq")

        // Messages 0-2: GameStateMessage diffs
        for (i in 0..2) {
            assertEquals(result.messages[i].type, GREMessageType.GameStateMessage_695e, "msg[$i] should be GSM")
            assertEquals(result.messages[i].gameStateMessage.type, GameStateType.Diff, "msg[$i] should be Diff")
        }

        // gs1: GameComplete with PendingLoss team + LossOfGame annotation
        val gs1 = result.messages[0].gameStateMessage
        assertTrue(gs1.hasGameInfo(), "gs1 should have gameInfo")
        assertEquals(gs1.gameInfo.matchState, Messages.MatchState.GameComplete)
        assertEquals(gs1.gameInfo.stage, Messages.GameStage.GameOver)
        assertEquals(gs1.gameInfo.resultsCount, 1, "gs1: 1 result (Game scope)")
        assertTrue(gs1.teamsCount > 0, "gs1 should have teams")
        assertTrue(gs1.annotationsCount > 0, "gs1 should have LossOfGame annotation")

        // gs2: MatchComplete with 2 results (Game + Match)
        val gs2 = result.messages[1].gameStateMessage
        assertEquals(gs2.gameInfo.matchState, Messages.MatchState.MatchComplete)
        assertEquals(gs2.gameInfo.resultsCount, 2, "gs2: 2 results (Game + Match)")

        // gs3: bare diff with pendingMessageCount=1
        val gs3 = result.messages[2].gameStateMessage
        assertEquals(gs3.pendingMessageCount, 1)
        assertFalse(gs3.hasGameInfo(), "gs3 should be bare diff")

        // IntermissionReq
        val intermission = result.messages[3]
        assertEquals(intermission.type, GREMessageType.IntermissionReq_695e)
        assertTrue(intermission.hasIntermissionReq())
        assertEquals(intermission.intermissionReq.optionsCount, 2, "Should have 2 user options")
        assertEquals(
            intermission.intermissionReq.intermissionPrompt.promptId,
            PromptIds.MATCH_RESULT_WIN_LOSS,
        )
    }

    /** gameOverBundle gsIds are strictly ascending. */
    @Test(groups = ["unit"])
    fun gameOverBundleGsIdsAscending() {
        val counter = MessageCounter(initialGsId = 10, initialMsgId = 0)
        val result = BundleBuilder.gameOverBundle(
            winningTeam = 1,
            matchId = "test-match",
            seatId = 1,
            counter = counter,
        )

        var prevGsId = 0
        for (msg in result.messages) {
            if (msg.hasGameStateMessage()) {
                val gsId = msg.gameStateMessage.gameStateId
                assertTrue(gsId > prevGsId, "gsId $gsId should be > previous $prevGsId")
                prevGsId = gsId
            }
        }
    }

    /** gameOverBundle prevGameStateId chains correctly. */
    @Test(groups = ["unit"])
    fun gameOverBundlePrevGsIdChain() {
        val counter = MessageCounter(initialGsId = 10, initialMsgId = 0)
        val result = BundleBuilder.gameOverBundle(
            winningTeam = 2,
            matchId = "test-match",
            seatId = 1,
            counter = counter,
        )

        val gsms = result.messages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
        assertEquals(gsms.size, 3)
        // gs1.prevGsId = initial (10)
        assertEquals(gsms[0].prevGameStateId, 10)
        // gs2.prevGsId = gs1.gsId
        assertEquals(gsms[1].prevGameStateId, gsms[0].gameStateId)
        // gs3.prevGsId = gs2.gsId
        assertEquals(gsms[2].prevGameStateId, gsms[1].gameStateId)
    }

    /** gameOverBundle with Concede reason. */
    @Test(groups = ["unit"])
    fun gameOverBundleConcedeReason() {
        val counter = MessageCounter(initialGsId = 10, initialMsgId = 0)
        val result = BundleBuilder.gameOverBundle(
            winningTeam = 1,
            matchId = "test-match",
            seatId = 1,
            counter = counter,
            reason = Messages.ResultReason.Concede,
            losingPlayerSeatId = 2,
            lossReason = 3,
        )

        val gs1 = result.messages[0].gameStateMessage
        val gameResult = gs1.gameInfo.resultsList.first()
        assertEquals(gameResult.reason, Messages.ResultReason.Concede)
    }

    // --- Conformance tests (board-based) ---

    /** declareAttackersBundle has correct GRE message types. */
    @Test
    fun declareAttackersBundleShape() {
        val (b, game, counter) = startWithBoard { _, _, _ -> }

        val result = BundleBuilder.declareAttackersBundle(game, b, TEST_MATCH_ID, 1, counter)

        assertEquals(result.messages.size, 2, "Attackers bundle should have 2 messages")
        assertEquals(result.messages[0].type, GREMessageType.GameStateMessage_695e)
        assertEquals(result.messages[1].type, GREMessageType.DeclareAttackersReq_695e)
        assertEquals(result.messages[1].prompt.promptId, 6)
    }

    /** declareBlockersBundle has correct GRE message types. */
    @Test
    fun declareBlockersBundleShape() {
        val (b, game, counter) = startWithBoard { _, _, _ -> }

        val result = BundleBuilder.declareBlockersBundle(game, b, TEST_MATCH_ID, 1, counter)

        assertEquals(result.messages.size, 2, "Blockers bundle should have 2 messages")
        assertEquals(result.messages[0].type, GREMessageType.GameStateMessage_695e)
        assertEquals(result.messages[1].type, GREMessageType.DeclareBlockersReq_695e)
        assertEquals(result.messages[1].prompt.promptId, 7)
    }

    /** selectTargetsBundle has correct GRE message types and prompt id. */
    @Test
    fun selectTargetsBundleShape() {
        val (b, game, counter) = startWithBoard { _, _, _ -> }

        val candidateRefs = listOf(
            forge.nexus.bridge.PromptCandidateRefDto(0, "card", 999, "Battlefield"),
        )
        val prompt = forge.nexus.bridge.InteractivePromptBridge.PendingPrompt(
            promptId = "test-prompt",
            request = forge.nexus.bridge.PromptRequest(
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

        assertEquals(result.messages.size, 2)
        assertEquals(result.messages[0].type, GREMessageType.GameStateMessage_695e)
        assertEquals(result.messages[1].type, GREMessageType.SelectTargetsReq_695e)
        assertEquals(result.messages[1].prompt.promptId, PromptIds.SELECT_TARGETS)
        assertEquals(result.messages[1].allowCancel, Messages.AllowCancel.Abort)
        assertTrue(result.messages[1].allowUndo)
    }

    /** echoAttackersBundle includes provisional combat objects for selected attackers. */
    @Test
    fun echoAttackersBundleWithCreatures() {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Llanowar Elves", human, ZoneType.Battlefield)
            addCard("Elvish Mystic", human, ZoneType.Battlefield)
        }

        val creatures = game.humanPlayer.getZone(ZoneType.Battlefield).cards.filter { it.isCreature }
        val allIds = creatures.map { b.getOrAllocInstanceId(it.id) }
        val selectedIds = listOf(allIds.first()) // select only first

        val result = BundleBuilder.echoAttackersBundle(game, b, 1, counter, selectedIds, allIds)

        assertEquals(result.messages.size, 2)
        assertEquals(result.messages[0].type, GREMessageType.GameStateMessage_695e)
        assertEquals(result.messages[1].type, GREMessageType.DeclareAttackersReq_695e)

        // GSM should contain provisional objects for legal attackers
        val gsm = result.messages[0].gameStateMessage
        assertEquals(gsm.type, GameStateType.Diff)
        assertTrue(gsm.gameObjectsCount > 0, "Should have provisional combat objects")
    }

    /** echoBlockersBundle includes provisional combat objects for assigned blockers. */
    @Test
    fun echoBlockersBundleWithCreatures() {
        val (b, game, counter) = startWithBoard { _, human, _ ->
            addCard("Llanowar Elves", human, ZoneType.Battlefield)
        }

        val blocker = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
        val blockerId = b.getOrAllocInstanceId(blocker.id)
        val blockAssignments = mapOf(blockerId to 999) // blocker → fake attacker

        val result = BundleBuilder.echoBlockersBundle(game, b, 1, counter, blockAssignments)

        assertEquals(result.messages.size, 2)
        assertEquals(result.messages[0].type, GREMessageType.GameStateMessage_695e)
        assertEquals(result.messages[1].type, GREMessageType.DeclareBlockersReq_695e)

        val gsm = result.messages[0].gameStateMessage
        assertTrue(gsm.gameObjectsCount > 0, "Should have provisional blocker objects")
    }

    /** selectNBundle has correct GRE message types and prompt id. */
    @Test
    fun selectNBundleShape() {
        val (b, game, counter) = startWithBoard { _, _, _ -> }

        val req = SelectNReq.newBuilder()
            .setMinSel(1)
            .setMaxSel(1)
            .build()
        val result = BundleBuilder.selectNBundle(game, b, TEST_MATCH_ID, 1, counter, req)

        assertEquals(result.messages.size, 2)
        assertEquals(result.messages[0].type, GREMessageType.GameStateMessage_695e)
        assertEquals(result.messages[1].type, GREMessageType.SelectNreq)
        assertEquals(result.messages[1].prompt.promptId, PromptIds.SELECT_N)
    }

    /** payCostsBundle has correct GRE message types and prompt id. */
    @Test
    fun payCostsBundleShape() {
        val (b, game, counter) = startWithBoard { _, _, _ -> }

        val req = Messages.PayCostsReq.newBuilder().build()
        val result = BundleBuilder.payCostsBundle(game, b, TEST_MATCH_ID, 1, counter, req)

        assertEquals(result.messages.size, 2)
        assertEquals(result.messages[0].type, GREMessageType.GameStateMessage_695e)
        assertEquals(result.messages[1].type, GREMessageType.PayCostsReq_695e)
        assertEquals(result.messages[1].prompt.promptId, PromptIds.PAY_COSTS)
    }
}
