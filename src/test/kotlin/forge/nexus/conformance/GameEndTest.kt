package forge.nexus.conformance

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
        assertEquals(firstGsm.gameInfo.matchState, MatchState.MatchComplete, "Match should be complete")

        // IntermissionReq should have result with winning team
        val req = intermission!!.intermissionReq
        assertTrue(req.hasResult(), "IntermissionReq should have result")
        assertEquals(req.result.result, ResultType.WinLoss, "Result type should be WinLoss")
        assertTrue(req.result.winningTeamId > 0, "Should have a winning team")

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
     * Uses multiple Raging Goblins (haste 1/1) to kill AI faster.
     *
     * Casts a new Raging Goblin each turn (haste). Damage ramps: 1, 2, 3, ...
     * Cumulative: 1+2+3+4+5+6 = 21 > 20 → AI dies by turn 6.
     */
    @Test(description = "Lethal damage produces MatchCompleted room state", timeOut = 120_000)
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

        // Verify IntermissionReq was sent
        val intermission = harness.allMessages.firstOrNull { it.hasIntermissionReq() }
        assertNotNull(intermission, "Should have IntermissionReq after lethal damage")
    }
}
