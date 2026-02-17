package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * Integration tests that validate our BundleBuilder output shape matches Arena patterns.
 *
 * These tests boot the Forge engine, run a BundleBuilder method, fingerprint the output,
 * and assert that the structural shape (message types, updateType, annotation presence)
 * matches known Arena patterns.
 *
 * Arena wire shape tests (no engine, read-only) are in [ArenaWireShapeTest].
 */
@Test(groups = ["integration"])
class ArenaShapeIntegrationTest : ConformanceTestBase() {

    @Test(description = "aiActionDiff produces 2-message SendHiFi pattern: diff + echo")
    fun aiActionDiffProducesTwoMessages() {
        val (b, game, gsId) = startGameAtMain1()

        val result = BundleBuilder.aiActionDiff(game, b, TEST_MATCH_ID, SEAT_ID, 1, gsId)
        val captured = fingerprint(result.messages)

        assertEquals(captured.size, 2, "aiActionDiff should produce 2 messages")
        assertEquals(captured[0].greMessageType, "GameStateMessage")
        assertEquals(captured[0].updateType, "SendHiFi")
        assertEquals(captured[1].greMessageType, "GameStateMessage")
        assertEquals(captured[1].updateType, "SendHiFi")
        assertTrue(captured[1].annotationTypes.isEmpty(), "Echo should have no annotations")
    }

    @Test(description = "declareAttackersBundle produces GS + DeclareAttackersReq with promptId=6")
    fun declareAttackersBundleShape() {
        val (b, game, gsId) = startGameAtMain1()

        val result = BundleBuilder.declareAttackersBundle(game, b, TEST_MATCH_ID, SEAT_ID, 1, gsId)
        val captured = fingerprint(result.messages)

        assertEquals(captured.size, 2, "Should produce 2 messages")
        assertEquals(captured[0].greMessageType, "GameStateMessage")
        assertEquals(captured[1].greMessageType, "DeclareAttackersReq")
        assertEquals(captured[1].promptId, 6, "DeclareAttackersReq promptId should be 6")
    }

    @Test(description = "edictalPass produces single EdictalMessage")
    fun edictalPassShape() {
        val result = BundleBuilder.edictalPass(1, 1, 10)
        val captured = fingerprint(result.messages)

        assertEquals(captured.size, 1, "edictalPass should produce 1 message")
        assertEquals(captured[0].greMessageType, "EdictalMessage")
    }
}
