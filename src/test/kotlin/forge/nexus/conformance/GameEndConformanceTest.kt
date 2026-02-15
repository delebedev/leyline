package forge.nexus.conformance

import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * Wire conformance: game end.
 *
 * Arena sends 4 messages at game end:
 *   1-3. GameStateMessage (Diff, SendAndRecord, no annotations) — final state flushes
 *   4.   IntermissionReq — signals match is over
 *
 * The SendAndRecord updateType (vs SendHiFi) indicates these are durable state updates
 * that the client should persist for reconnection. The IntermissionReq triggers the
 * post-game rewards/results screen.
 */
@Test(groups = ["integration", "conformance"])
class GameEndConformanceTest : ConformanceTestBase() {

    @Test(description = "Game end: 3x empty GS Diff SendAndRecord + IntermissionReq")
    fun arenaGameEndStructure() {
        val golden = loadGolden("arena-game-end")

        assertEquals(golden.size, 4, "Game end should have 4 messages")

        // 3 empty diffs with SendAndRecord
        for (i in 0..2) {
            assertEquals(golden[i].greMessageType, "GameStateMessage", "Message $i: GameStateMessage")
            assertEquals(golden[i].gsType, "Diff", "Message $i: Diff")
            assertEquals(golden[i].updateType, "SendAndRecord", "Message $i: SendAndRecord")
            assertTrue(golden[i].annotationTypes.isEmpty(), "Message $i: no annotation types")
            assertTrue(golden[i].annotationCategories.isEmpty(), "Message $i: no annotation categories")
        }

        // First message has gameInfo + players + timers (richest)
        assertTrue(golden[0].fieldPresence.contains("gameInfo"), "First flush has gameInfo")
        assertTrue(golden[0].fieldPresence.contains("players"), "First flush has players")
        assertTrue(golden[0].fieldPresence.contains("timers"), "First flush has timers")

        // IntermissionReq
        assertEquals(golden[3].greMessageType, "IntermissionReq")
        // IntermissionReq has no GameStateMessage fields
        assertEquals(golden[3].gsType, null, "IntermissionReq has no gsType")
        assertEquals(golden[3].updateType, null, "IntermissionReq has no updateType")
    }
}
