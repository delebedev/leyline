package forge.nexus.game

import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * Counter invariants for [NexusGamePlayback].
 *
 * The playback's atomic counters are written by two threads:
 *   - Game thread: captureAndPause increments after building each bundle
 *   - Handler thread: seedCounters sets values before unblocking the game thread
 *
 * Seeding with a stale value must never clobber a counter that the game thread
 * already advanced — otherwise gsIds collide and the client drops state.
 */
@Test(groups = ["unit"])
class NexusGamePlaybackCounterTest {

    /**
     * seedCounters must not overwrite a counter that already advanced past the
     * seeded value. This is the root cause of gsId collisions during AI turns:
     *
     *   1. Game thread fires TurnBegan → captureAndPause → atomics advance to N
     *   2. Handler drains, syncs local to N
     *   3. Game thread fires TurnPhase → captureAndPause → atomics advance to N+4
     *   4. Handler re-seeds with N (stale!) → clobbers N+4
     *   5. Next captureAndPause reads N → produces gsIds that already exist
     */
    @Test(description = "seedCounters with stale value must not clobber advanced counter")
    fun seedCountersDoesNotClobberAdvancedValue() {
        val playback = NexusGamePlayback(
            bridge = GameBridge(),
            matchId = "test",
            seatId = 1,
        )

        // Simulate: game thread advanced counters to 20
        playback.seedCounters(msgId = 20, gsId = 20)
        assertEquals(playback.getCounters(), 20 to 20)

        // Simulate: handler tries to re-seed with stale value (15)
        playback.seedCounters(msgId = 15, gsId = 15)

        // Counter must NOT go backwards
        val (msgId, gsId) = playback.getCounters()
        assertTrue(msgId >= 20, "msgId went backwards: $msgId < 20")
        assertTrue(gsId >= 20, "gsId went backwards: $gsId < 20")
    }

    @Test(description = "seedCounters with higher value advances counter normally")
    fun seedCountersAdvancesWithHigherValue() {
        val playback = NexusGamePlayback(
            bridge = GameBridge(),
            matchId = "test",
            seatId = 1,
        )

        playback.seedCounters(msgId = 10, gsId = 10)
        playback.seedCounters(msgId = 20, gsId = 20)

        val (msgId, gsId) = playback.getCounters()
        assertEquals(msgId, 20)
        assertEquals(gsId, 20)
    }
}
