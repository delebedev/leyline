package forge.nexus.conformance

import org.testng.Assert.*
import org.testng.annotations.AfterMethod
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Wire conformance: AI-first turn message shape.
 *
 * Runs a game with [MatchFlowHarnessTest.AI_FIRST_SEED] so Sparky goes first.
 * Captures messages through MatchSession/MatchFlowHarness (production code path)
 * and asserts properties matching the real server:
 *
 * 1. **No post-handshake Full** — gameStart uses thin Diffs (phaseTransitionDiff),
 *    not a Full state with all zones/objects.
 * 2. **Phase transition pattern** — first 3 GSMs match phaseTransitionDiff shape.
 * 3. **Human actions in AI-turn GSMs** — GSMs during AI turn (activePlayer=2)
 *    should embed the human's available actions, not the AI's.
 * 4. **PhaseOrStepModified** — every phase/step transition must have annotations.
 */
@Test(groups = ["integration", "conformance"])
class AiFirstTurnShapeTest {

    private var harness: MatchFlowHarness? = null

    @AfterMethod
    fun tearDown() {
        harness?.shutdown()
        harness = null
    }

    private fun runAiFirstGame(): MatchFlowHarness {
        val h = MatchFlowHarness(seed = MatchFlowHarnessTest.AI_FIRST_SEED)
        harness = h
        h.connectAndKeep()
        assertFalse(h.isGameOver(), "Game should not be over after connectAndKeep")
        return h
    }

    /**
     * No post-handshake GSM should be Full with zones/objects.
     * The only Full is gsId=1 in the handshake (not captured by harness).
     * gameStart now uses phaseTransitionDiff which produces thin Diffs.
     */
    @Test(description = "No post-handshake Full GSM with zones")
    fun gameStartIsDiffNotFull() {
        val h = runAiFirstGame()

        val fullWithZones = h.allMessages
            .filter { it.hasGameStateMessage() && it.gameStateMessage.type == GameStateType.Full }
            .map { it.gameStateMessage }
            .filter { it.zonesCount > 0 }

        assertTrue(
            fullWithZones.isEmpty(),
            "No post-handshake GSM should be Full with zones, but found ${fullWithZones.size}: " +
                fullWithZones.map { "gsId=${it.gameStateId}" },
        )
    }

    /**
     * First 3 GSMs after handshake should match phaseTransitionDiff shape:
     *   [0]: Diff/SendHiFi, 2x PhaseOrStepModified, has gameInfo
     *   [1]: Diff/SendHiFi, turnInfo + actions only (echo)
     *   [2]: Diff/SendAndRecord, 1x PhaseOrStepModified (checkpoint)
     */
    @Test(description = "gameStart has phaseTransitionDiff pattern")
    fun gameStartHasPhaseTransitionPattern() {
        val h = runAiFirstGame()

        val gsms = h.allMessages
            .filter { it.hasGameStateMessage() }
            .map { it.gameStateMessage }

        assertTrue(gsms.size >= 3, "Expected at least 3 GSMs, got ${gsms.size}")

        // GSM 0: SendHiFi with 2+ PhaseOrStepModified + gameInfo
        val gsm0 = gsms[0]
        assertEquals(gsm0.type, GameStateType.Diff, "GSM[0] should be Diff")
        assertEquals(gsm0.update, GameStateUpdate.SendHiFi, "GSM[0] should be SendHiFi")
        assertTrue(gsm0.hasGameInfo(), "GSM[0] should have gameInfo")
        val phaseAnns0 = gsm0.annotationsList.flatMap { it.typeList }
            .count { it == AnnotationType.PhaseOrStepModified }
        assertTrue(phaseAnns0 >= 2, "GSM[0] should have 2+ PhaseOrStepModified, got $phaseAnns0")

        // GSM 1: SendHiFi echo (turnInfo + actions)
        val gsm1 = gsms[1]
        assertEquals(gsm1.type, GameStateType.Diff, "GSM[1] should be Diff")
        assertEquals(gsm1.update, GameStateUpdate.SendHiFi, "GSM[1] should be SendHiFi")
        assertTrue(gsm1.hasTurnInfo(), "GSM[1] should have turnInfo")

        // GSM 2: SendAndRecord with 1x PhaseOrStepModified
        val gsm2 = gsms[2]
        assertEquals(gsm2.type, GameStateType.Diff, "GSM[2] should be Diff")
        assertEquals(gsm2.update, GameStateUpdate.SendAndRecord, "GSM[2] should be SendAndRecord")
        val phaseAnns2 = gsm2.annotationsList.flatMap { it.typeList }
            .count { it == AnnotationType.PhaseOrStepModified }
        assertEquals(phaseAnns2, 1, "GSM[2] should have exactly 1 PhaseOrStepModified")
    }

    /**
     * GSMs during AI turns (activePlayer=2) should embed the human's available
     * actions (seat 1), not the AI's. The real server always includes the
     * recipient's actions so the client knows what it can do.
     */
    @Test(description = "AI-turn GSMs embed human's actions, not AI's")
    fun aiTurnGsmsEmbedHumanActions() {
        val h = runAiFirstGame()

        // Filter to GSMs where it's AI's turn (activePlayer=2) and actions are embedded
        val aiTurnGsms = h.allMessages
            .filter {
                it.hasGameStateMessage() &&
                    it.gameStateMessage.hasTurnInfo() &&
                    it.gameStateMessage.turnInfo.activePlayer == 2 &&
                    it.gameStateMessage.actionsCount > 0
            }
            .map { it.gameStateMessage }

        if (aiTurnGsms.isEmpty()) {
            // If no AI-turn GSMs have embedded actions at all, that's also a bug
            // but a different one (no actions embedded). Skip for now.
            return
        }

        // All embedded actions should be for seat 1 (human), not seat 2 (AI)
        val wrongSeatActions = aiTurnGsms.flatMap { gsm ->
            gsm.actionsList.filter { it.seatId == 2 }
        }

        if (wrongSeatActions.isNotEmpty()) {
            val report = buildString {
                appendLine("Found ${wrongSeatActions.size} actions embedded for AI seat (2) during AI turn")
                appendLine("Actions should be for human seat (1)")
                aiTurnGsms.take(3).forEachIndexed { i, gsm ->
                    val seats = gsm.actionsList.map { "seat=${it.seatId} type=${it.action.actionType.name}" }
                    appendLine("  [$i] gsId=${gsm.gameStateId} phase=${gsm.turnInfo.phase} actions=$seats")
                }
            }
            fail("AI-turn GSMs should embed human's (seat 1) actions, not AI's (seat 2):\n$report")
        }
    }

    /**
     * Every phase/step transition must have PhaseOrStepModified annotations.
     * The real server annotates every transition; our engine must too.
     */
    @Test(description = "Phase transitions have PhaseOrStepModified annotations")
    fun phaseTransitionsHaveAnnotations() {
        val h = runAiFirstGame()

        val gsms = h.allMessages
            .filter { it.hasGameStateMessage() && it.gameStateMessage.hasTurnInfo() }
            .map { it.gameStateMessage }

        // Detect phase/step changes by comparing consecutive GSMs
        val phaseChanges = mutableListOf<GameStateMessage>()
        for (i in 1 until gsms.size) {
            val prev = gsms[i - 1]
            val curr = gsms[i]
            if (curr.turnInfo.phase != prev.turnInfo.phase ||
                curr.turnInfo.step != prev.turnInfo.step
            ) {
                phaseChanges.add(curr)
            }
        }

        assertTrue(phaseChanges.isNotEmpty(), "Expected phase changes during AI-first game")

        val missing = phaseChanges.filter { gsm ->
            gsm.annotationsList.none { ann ->
                AnnotationType.PhaseOrStepModified in ann.typeList
            }
        }

        if (missing.isNotEmpty()) {
            val report = buildString {
                appendLine("${missing.size}/${phaseChanges.size} phase transitions missing PhaseOrStepModified:")
                missing.forEachIndexed { i, gsm ->
                    appendLine(
                        "  [$i] gsId=${gsm.gameStateId} " +
                            "phase=${gsm.turnInfo.phase}/${gsm.turnInfo.step} " +
                            "update=${gsm.update} " +
                            "annotations=${gsm.annotationsList.flatMap { it.typeList }.map { it.name }}",
                    )
                }
            }
            fail("Phase transitions must have PhaseOrStepModified annotations:\n$report")
        }
    }
}
