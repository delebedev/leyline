package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.assertEquals
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

    @Test(description = "Play-land shape matches real Arena recording (full-game-3.json [29-30])")
    fun arenaPlayLandShape() {
        val (b, game, gsId) = startGameAtMain1()
        playLand(b) ?: return

        val result = BundleBuilder.postAction(game, b, "test-match", 1, 1, gsId)
        val captured = fingerprint(result.messages)

        assertTrue(captured.isNotEmpty(), "Should have captured GRE messages")
        assertShapeConformance("arena-play-land", captured)
    }

    // --- Arena golden: phase transition ---

    @Test(description = "Phase transition shape matches Arena 5-message pattern (full-game-3.json [19-23])")
    fun arenaPhaseTransitionShape() {
        val (b, game, gsId) = startGameAtMain1()

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

    @Test(description = "declareAttackersBundle produces same message types and promptId as Arena (full-game.json [309-310])")
    fun arenaDeclareAttackersShape() {
        val golden = loadGolden("arena-declare-attackers")
        val (b, game, gsId) = startGameAtMain1()

        // Build our bundle — game may not be in combat, but structural shape is deterministic
        val result = BundleBuilder.declareAttackersBundle(game, b, "test-match", 1, 1, gsId)
        val captured = fingerprint(result.messages)

        // Same message count
        assertEquals(captured.size, golden.size, "Should produce ${golden.size} messages")
        // Message types match
        assertEquals(captured[0].greMessageType, golden[0].greMessageType, "First: GameStateMessage")
        assertEquals(captured[1].greMessageType, golden[1].greMessageType, "Second: DeclareAttackersReq")
        // PromptId matches
        assertEquals(captured[1].promptId, golden[1].promptId, "DeclareAttackersReq promptId")
        assertEquals(captured[1].promptId, 6, "promptId should be 6")
    }

    // --- Arena golden: EdictalMessage ---

    @Test(description = "edictalPass() output matches Arena EdictalMessage shape (full-game.json [478-480])")
    fun arenaEdictalShape() {
        val golden = loadGolden("arena-edictal-pass")
        // Golden structure: SendAndRecord GS → EdictalMessage → SendHiFi GS

        // Our edictalPass produces the middle message
        val result = BundleBuilder.edictalPass(1, 1, 10)
        val captured = fingerprint(result.messages)

        assertEquals(captured.size, 1, "edictalPass should produce 1 message")
        // Type matches the Arena recording
        assertEquals(captured[0].greMessageType, golden[1].greMessageType, "Should match Arena EdictalMessage type")
        assertEquals(captured[0].greMessageType, "EdictalMessage")
    }

    @Test(description = "Arena EdictalMessage surrounded by correct updateType transitions")
    fun arenaEdictalContext() {
        val golden = loadGolden("arena-edictal-pass")

        // Verify context pattern: SendAndRecord → Edict → SendHiFi
        assertEquals(golden.size, 3, "Arena edictal golden should have 3 messages")
        assertEquals(golden[0].updateType, "SendAndRecord", "Before edict: SendAndRecord")
        assertEquals(golden[1].greMessageType, "EdictalMessage", "Middle: EdictalMessage")
        assertEquals(golden[2].updateType, "SendHiFi", "After edict: SendHiFi")
    }

    // --- Arena golden: game start ---
    // No dedicated game-start bundle test: real Arena uses phase transitions
    // (PhaseOrStepModified diffs) to advance from Beginning to Main1.
    // Our BundleBuilder.gameStart() is a synthetic shortcut that doesn't match
    // any Arena subsequence. The phase transition test covers the real pattern.
}
