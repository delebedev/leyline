package forge.nexus.conformance

import forge.nexus.game.BundleBuilder
import forge.nexus.game.MessageCounter
import org.testng.Assert.assertEquals
import org.testng.annotations.Test

/**
 * Integration tests that validate our BundleBuilder output shape matches client patterns.
 *
 * These tests boot the Forge engine, run a BundleBuilder method, fingerprint the output,
 * and assert that the structural shape (message types, updateType, annotation presence)
 * matches known client patterns.
 *
 * Client wire shape tests (no engine, read-only) are in [WireShapeTest].
 */
@Test(groups = ["integration"])
class ShapeIntegrationTest : ConformanceTestBase() {

    @Test(description = "aiActionDiff produces single SendHiFi GSM (no echo)")
    fun aiActionDiffProducesSingleMessage() {
        val (b, game, counter) = startGameAtMain1()

        val result = BundleBuilder.aiActionDiff(game, b, TEST_MATCH_ID, SEAT_ID, counter)
        val captured = fingerprint(result.messages)

        assertEquals(captured.size, 1, "aiActionDiff should produce 1 message")
        assertEquals(captured[0].greMessageType, "GameStateMessage")
        assertEquals(captured[0].updateType, "SendHiFi")
    }

    @Test(description = "declareAttackersBundle produces GS + DeclareAttackersReq with promptId=6")
    fun declareAttackersBundleShape() {
        val (b, game, counter) = startGameAtMain1()

        val result = BundleBuilder.declareAttackersBundle(game, b, TEST_MATCH_ID, SEAT_ID, counter)
        val captured = fingerprint(result.messages)

        assertEquals(captured.size, 2, "Should produce 2 messages")
        assertEquals(captured[0].greMessageType, "GameStateMessage")
        assertEquals(captured[1].greMessageType, "DeclareAttackersReq")
        assertEquals(captured[1].promptId, 6, "DeclareAttackersReq promptId should be 6")
    }

    @Test(description = "edictalPass produces single EdictalMessage")
    fun edictalPassShape() {
        val result = BundleBuilder.edictalPass(1, MessageCounter(initialGsId = 10, initialMsgId = 0))
        val captured = fingerprint(result.messages)

        assertEquals(captured.size, 1, "edictalPass should produce 1 message")
        assertEquals(captured[0].greMessageType, "EdictalMessage")
    }
}
