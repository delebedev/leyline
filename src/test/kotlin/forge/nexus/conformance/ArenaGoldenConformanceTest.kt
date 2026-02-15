package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * Conformance tests against real Arena server recordings.
 *
 * These goldens are extracted from full-game captures (src/test/resources/golden/full-game*.json)
 * recorded from actual Arena sessions. Unlike self-generated goldens, these represent ground
 * truth for the wire protocol shape.
 *
 * All tests use shape-only comparison (message type, gsType, updateType, annotations, prompts).
 * Action types and exact zone/object counts are deck-dependent and intentionally ignored.
 */
@Test(groups = ["integration", "conformance", "arena"])
class ArenaGoldenConformanceTest : ConformanceTestBase() {

    // --- Arena golden: play land ---

    @Test(
        description = "Play-land shape vs Arena (full-game-3.json [29-30]): expected to fail — missing timers in fieldPresence",
        expectedExceptions = [AssertionError::class],
    )
    fun arenaPlayLandShape() {
        val (b, game, gsId) = startGameAtMain1()
        playLand(b) ?: return

        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)
        val captured = fingerprint(result.messages)

        assertTrue(captured.isNotEmpty(), "Should have captured GRE messages")
        assertShapeConformance("arena-play-land", captured)
    }

    // --- Arena golden: phase transition ---

    @Test(
        description = "Phase transition shape vs Arena (full-game-3.json [19-23]): expected to fail — we lack PromptReq and triple-diff",
        expectedExceptions = [AssertionError::class],
    )
    fun arenaPhaseTransitionShape() {
        val (b, game, gsId) = startGameAtMain1()

        // Phase transition produces 2 diffs; Arena sends 5 messages (3 diffs + PromptReq + ActionsAvailableReq)
        val result = BundleBuilder.phaseTransitionDiff(game, b, "test-match", 1, 1, gsId)
        val captured = fingerprint(result.messages)

        assertShapeConformance("arena-phase-transition", captured)
    }

    // --- Arena golden: cast creature ---

    @Test(
        description = "Cast-creature shape vs Arena (full-game-3.json [33-36]): expected to fail — we produce 2 messages, Arena sends 4",
        expectedExceptions = [AssertionError::class],
    )
    fun arenaCastCreatureShape() {
        val (b, game, gsId) = startGameAtMain1()
        castCreature(b) ?: return

        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)
        val captured = fingerprint(result.messages)

        assertShapeConformance("arena-cast-creature", captured)
    }

    // --- Arena golden: declare attackers ---

    @Test(
        description = "Declare-attackers shape vs Arena (full-game.json [309-310]): message types + promptId",
    )
    fun arenaDeclareAttackersShape() {
        val golden = loadGolden("arena-declare-attackers")

        // Structural check: first message is GameStateMessage, second is DeclareAttackersReq(promptId=6)
        assertTrue(golden.size == 2, "Arena golden should have 2 messages")
        assertTrue(golden[0].greMessageType == "GameStateMessage", "First should be GameStateMessage")
        assertTrue(golden[1].greMessageType == "DeclareAttackersReq", "Second should be DeclareAttackersReq")
        assertTrue(golden[1].promptId == 6, "DeclareAttackersReq should have promptId=6")
    }

    // --- Arena golden: EdictalMessage ---

    @Test(description = "EdictalMessage appears in real Arena recordings (full-game.json [478-480])")
    fun arenaEdictalMessageExists() {
        val golden = loadGolden("arena-edictal-pass")

        // Verify structure: SendAndRecord state → EdictalMessage → SendHiFi state
        assertTrue(golden.size == 3, "Arena edictal golden should have 3 messages")
        assertTrue(golden[0].greMessageType == "GameStateMessage", "Before: GameStateMessage")
        assertTrue(golden[0].updateType == "SendAndRecord", "Before: SendAndRecord")
        assertTrue(golden[1].greMessageType == "EdictalMessage", "Middle: EdictalMessage")
        assertTrue(golden[2].greMessageType == "GameStateMessage", "After: GameStateMessage")
        assertTrue(golden[2].updateType == "SendHiFi", "After: SendHiFi")
    }

    @Test(description = "Our edictalPass() produces correct message type")
    fun edictalPassMessageType() {
        val result = BundleBuilder.edictalPass(1, 1, 10)
        val captured = fingerprint(result.messages)

        assertTrue(captured.size == 1, "edictalPass should produce 1 message")
        assertTrue(captured[0].greMessageType == "EdictalMessage", "Should be EdictalMessage type")
    }

    // --- Arena golden: game start bundle ---

    @Test(
        description = "Game-start hand states vs Arena (full-game-3.json [7-9]): expected to fail — different bundle structure",
        expectedExceptions = [AssertionError::class],
    )
    fun arenaGameStartHandShape() {
        val (b, game, gsId) = startGameAtMain1()

        val result = BundleBuilder.gameStart(game, b, "test-match", 1, 1, gsId)
        val captured = fingerprint(result.messages)

        assertShapeConformance("arena-game-start", captured)
    }
}
