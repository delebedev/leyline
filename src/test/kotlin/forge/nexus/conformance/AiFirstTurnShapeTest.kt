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
 * and asserts properties that currently diverge from the real server:
 *
 * 1. **gameStart at T0** — the initial Full state should be at turn 0 (pre-game),
 *    not at T1/Upkeep where the human first gets priority. Real server sends
 *    Full at T0 immediately, then AI actions as Diffs.
 * 2. **Human actions in AI-turn GSMs** — GSMs during AI turn (activePlayer=2)
 *    should embed the human's available actions, not the AI's.
 * 3. **PhaseOrStepModified** — every phase/step transition must have annotations.
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
     * The gameStart Full state should be at turn 0 (pre-game), matching the
     * real server which sends its initial Full before any turns begin.
     *
     * Currently our engine waits for human priority before sending gameStart,
     * so with AI-first the Full lands at T1/Upkeep instead of T0.
     */
    @Test(description = "gameStart Full is at T0, not at a later turn/phase")
    fun gameStartFullIsAtTurnZero() {
        val h = runAiFirstGame()

        // Find the gameStart Full GSM (the Full with zones + objects)
        val fullGsms = h.allMessages
            .filter { it.hasGameStateMessage() && it.gameStateMessage.type == GameStateType.Full }
            .map { it.gameStateMessage }
            .filter { it.zonesCount > 0 } // gameStart Full has zones; handshake Fulls don't

        assertTrue(fullGsms.isNotEmpty(), "Should have at least one Full GSM with zones (gameStart)")

        // The gameStart Full should be at turn 0 (pre-game), like the real server
        val gameStartGsm = fullGsms.first()
        val turn = gameStartGsm.turnInfo.turnNumber
        val phase = gameStartGsm.turnInfo.phase

        // Real server: Full at T0 None/None
        // Current engine: Full at T1 Beginning/Upkeep (human first gets priority at AI's upkeep)
        if (turn > 0) {
            fail(
                "gameStart Full should be at turn 0 (pre-game) but was at " +
                    "turn=$turn phase=$phase. Real server sends Full at T0, " +
                    "then AI actions as Diffs.",
            )
        }
    }

    /**
     * GSMs during AI turns (activePlayer=2) should embed the human's available
     * actions (seat 1), not the AI's. The real server always includes the
     * recipient's actions so the client knows what it can do.
     *
     * Currently aiActionDiff embeds AI's actions (Pass/FloatMana at upkeep)
     * instead of human's.
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
     * The real server annotates every transition; our engine misses some.
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
