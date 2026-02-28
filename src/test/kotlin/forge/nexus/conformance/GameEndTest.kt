package forge.nexus.conformance

import forge.nexus.game.mapper.PromptIds
import org.testng.Assert.*
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Tests for game-end protocol: IntermissionReq + MatchCompleted room state.
 *
 * The Arena client requires [MatchGameRoomStateType.MatchCompleted] to trigger
 * the result screen. Without it, the client stays on the game board after the
 * server sends the game-over GRE sequence.
 */
@Test(groups = ["integration"])
class GameEndTest {

    private lateinit var harness: MatchFlowHarness

    @AfterMethod(alwaysRun = true)
    fun tearDown() {
        if (::harness.isInitialized) harness.shutdown()
    }

    /**
     * Concede triggers sendGameOver which must send:
     * 1. 3x GameStateMessage (Diff, SendAndRecord) with GameInfo.stage=GameOver
     * 2. IntermissionReq with match result
     * 3. MatchGameRoomStateChangedEvent with stateType=MatchCompleted
     */
    @Test(description = "Concede sends IntermissionReq + MatchCompleted room state")
    fun concedeProducesMatchCompleted() {
        harness = MatchFlowHarness(seed = 42L, validating = false)
        harness.connectAndKeep()

        // Concede triggers sendGameOver()
        val snap = harness.messageSnapshot()
        harness.session.onConcede()
        harness.drainSink()

        // Verify GRE messages: 3x GSM + IntermissionReq
        val msgs = harness.messagesSince(snap)
        val gsmCount = msgs.count { it.hasGameStateMessage() }
        val intermission = msgs.firstOrNull { it.hasIntermissionReq() }

        assertTrue(gsmCount >= 3, "Should have 3+ GameStateMessage diffs, got $gsmCount")
        assertNotNull(intermission, "Should have IntermissionReq")

        // First GSM should have GameInfo with stage=GameOver
        val firstGsm = msgs.first { it.hasGameStateMessage() }.gameStateMessage
        assertTrue(firstGsm.hasGameInfo(), "First GSM should have gameInfo")
        assertEquals(firstGsm.gameInfo.stage, GameStage.GameOver, "Stage should be GameOver")
        assertEquals(firstGsm.gameInfo.matchState, MatchState.GameComplete, "First GSM matchState should be GameComplete")

        // IntermissionReq should have result with winning team + reason
        val req = intermission!!.intermissionReq
        assertTrue(req.hasResult(), "IntermissionReq should have result")
        assertEquals(req.result.result, ResultType.WinLoss, "Result type should be WinLoss")
        assertTrue(req.result.winningTeamId > 0, "Should have a winning team")
        assertEquals(req.result.reason, ResultReason.Concede, "ResultSpec reason should be Concede")

        // IntermissionReq should have options + intermissionPrompt
        assertTrue(req.optionsCount > 0, "IntermissionReq should have options")
        assertTrue(req.optionsCount >= 2, "IntermissionReq should have 2+ options")
        assertTrue(req.hasIntermissionPrompt(), "IntermissionReq should have intermissionPrompt")
        assertEquals(req.intermissionPrompt.promptId, PromptIds.MATCH_RESULT_WIN_LOSS, "intermissionPrompt promptId should be 27 (MatchResultWinLoss)")
        assertTrue(req.intermissionPrompt.parametersCount > 0, "intermissionPrompt should have WinningTeamId parameter")

        // prevGameStateId chain: gs1.prev = last-known, gs2.prev = gs1, gs3.prev = gs2
        val gsms = msgs.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
        assertTrue(gsms.size >= 3, "Should have 3+ GSMs, got ${gsms.size}")
        assertEquals(gsms[1].prevGameStateId, gsms[0].gameStateId, "gs2.prev should be gs1")
        assertEquals(gsms[2].prevGameStateId, gsms[1].gameStateId, "gs3.prev should be gs2")

        // MatchCompleted room state should be in allRawMessages
        val rawMsgs = harness.allRawMessages
        val matchCompleted = rawMsgs.firstOrNull {
            it.hasMatchGameRoomStateChangedEvent() &&
                it.matchGameRoomStateChangedEvent.gameRoomInfo.stateType ==
                MatchGameRoomStateType.MatchCompleted
        }
        assertNotNull(
            matchCompleted,
            "Should send MatchGameRoomStateChangedEvent with MatchCompleted, " +
                "got ${rawMsgs.size} raw messages: ${rawMsgs.map { it.matchGameRoomStateChangedEvent?.gameRoomInfo?.stateType }}",
        )

        // Verify FinalMatchResult
        val finalResult = matchCompleted!!.matchGameRoomStateChangedEvent.gameRoomInfo.finalMatchResult
        assertEquals(
            finalResult.matchCompletedReason,
            MatchCompletedReasonType.Success_a26d,
            "Completion reason should be Success",
        )
        assertTrue(finalResult.resultListCount > 0, "Should have result list")
        assertEquals(
            finalResult.getResultList(0).result,
            ResultType.WinLoss,
            "Result should be WinLoss",
        )
    }

    /**
     * Natural game-over via lethal damage should also produce MatchCompleted.
     *
     * DISABLED: multi-turn loop to reach lethal is slow and flaky — times out
     * at 120s. Needs puzzle-based rewrite: start AI at 1 life with a Lightning
     * Bolt in hand, or use a combat puzzle with lethal on board.
     * concedeProducesMatchCompleted already covers the MatchCompleted protocol;
     * this test's value is specifically the lethal-damage ResultReason path.
     */
    @Test(description = "Lethal damage produces MatchCompleted room state", enabled = false)
    fun lethalDamageProducesMatchCompleted() {
        harness = MatchFlowHarness(
            seed = 42L,
            deckList = CombatFlowTest.COMBAT_DECK,
            validating = false,
        )
        harness.connectAndKeep()

        // AI: passive — play lands, never attack, always pass
        harness.installScriptedAi(
            List(60) { i ->
                when (i % 3) {
                    0 -> ScriptedAction.PlayLand("Mountain")
                    1 -> ScriptedAction.DeclareNoAttackers
                    else -> ScriptedAction.PassPriority
                }
            },
        )

        // Turn 1: play Mountain + cast Raging Goblin
        assertTrue(harness.playLand(), "Should play Mountain")
        assertTrue(harness.castSpellByName("Raging Goblin"), "Should cast Raging Goblin")
        harness.passPriority() // resolve

        // Game loop: each human turn, play a land + cast another Raging Goblin + attack all
        var lastDaReqSnap = 0
        repeat(500) {
            if (harness.isGameOver()) return@repeat

            harness.passPriority()
            if (harness.isGameOver()) return@repeat

            // On human turns, try to play land + cast another goblin
            if (!harness.isAiTurn() && !harness.isGameOver()) {
                harness.playLand()
                if (harness.castSpellByName("Raging Goblin")) {
                    harness.passPriority() // resolve
                }
            }

            if (harness.isGameOver()) return@repeat

            // Check for new DeclareAttackersReq
            val newMsgs = harness.messagesSince(lastDaReqSnap)
            val daReq = newMsgs.lastOrNull { it.hasDeclareAttackersReq() }
            if (daReq != null) {
                lastDaReqSnap = harness.messageSnapshot()
                val eligible = daReq.declareAttackersReq.attackersList.map { it.attackerInstanceId }
                if (eligible.isNotEmpty()) {
                    harness.declareAttackers(eligible)
                }
            }
        }

        assertTrue(harness.isGameOver(), "Game should be over after repeated attacks")

        // Verify MatchCompleted was sent
        val rawMsgs = harness.allRawMessages
        val matchCompleted = rawMsgs.firstOrNull {
            it.hasMatchGameRoomStateChangedEvent() &&
                it.matchGameRoomStateChangedEvent.gameRoomInfo.stateType ==
                MatchGameRoomStateType.MatchCompleted
        }
        assertNotNull(
            matchCompleted,
            "Lethal damage should produce MatchCompleted room state",
        )

        // Verify IntermissionReq was sent with correct fields
        val intermission = checkNotNull(harness.allMessages.firstOrNull { it.hasIntermissionReq() }) {
            "Should have IntermissionReq after lethal damage"
        }
        val req = intermission.intermissionReq
        assertEquals(req.result.reason, ResultReason.Game_ae0a, "ResultSpec reason should be Game_ae0a for lethal damage")

        // IntermissionReq should have options + intermissionPrompt
        assertTrue(req.optionsCount >= 2, "IntermissionReq should have 2+ options")
        assertTrue(req.hasIntermissionPrompt(), "IntermissionReq should have intermissionPrompt")
        assertEquals(req.intermissionPrompt.promptId, PromptIds.MATCH_RESULT_WIN_LOSS, "intermissionPrompt promptId should be 27")
        assertTrue(req.intermissionPrompt.parametersCount > 0, "intermissionPrompt should have WinningTeamId parameter")

        // Game-over GSMs: the 3 GSMs immediately before IntermissionReq
        // (gs3 has no gameInfo, so we can't filter by stage=GameOver)
        val allMsgs = harness.allMessages
        val intermissionIdx = allMsgs.indexOfFirst { it.hasIntermissionReq() }
        assertTrue(intermissionIdx >= 3, "Should have 3+ GSMs before IntermissionReq")
        val gameOverGsms = allMsgs.subList(intermissionIdx - 3, intermissionIdx)
            .filter { it.hasGameStateMessage() }
            .map { it.gameStateMessage }
        assertEquals(gameOverGsms.size, 3, "Should have exactly 3 game-over GSMs")

        // First game-over GSM should have LossOfGame annotation
        val lossAnnotation = gameOverGsms[0].annotationsList
            .firstOrNull { it.typeList.contains(AnnotationType.LossOfGame_af5a) }
        assertNotNull(lossAnnotation, "First game-over GSM should have LossOfGame annotation")

        // prevGameStateId chain
        assertEquals(gameOverGsms[1].prevGameStateId, gameOverGsms[0].gameStateId, "gs2.prev should be gs1")
        assertEquals(gameOverGsms[2].prevGameStateId, gameOverGsms[1].gameStateId, "gs3.prev should be gs2")
    }
}
