package forge.nexus.conformance

import forge.nexus.game.GameBridge
import forge.nexus.server.ListMessageSink
import forge.nexus.server.MatchRegistry
import forge.nexus.server.MatchSession
import org.testng.Assert.*
import org.testng.annotations.Test

@Test(groups = ["unit"])
class MatchRegistryTest {

    @Test(description = "getOrCreateBridge creates on first call, reuses on second")
    fun bridgeCreatedOnceReused() {
        val registry = MatchRegistry()
        var created = 0
        val b1 = registry.getOrCreateBridge("m1") {
            created++
            GameBridge()
        }
        val b2 = registry.getOrCreateBridge("m1") {
            created++
            GameBridge()
        }
        assertSame(b1, b2)
        assertEquals(created, 1)
    }

    @Test(description = "registerSession + getPeer returns correct session")
    fun registerAndGetPeer() {
        val registry = MatchRegistry()
        val sink = ListMessageSink()
        val s1 = MatchSession(seatId = 1, matchId = "m1", sink = sink, registry = registry, paceDelayMs = 0)
        val s2 = MatchSession(seatId = 2, matchId = "m1", sink = sink, registry = registry, paceDelayMs = 0)
        registry.registerSession("m1", 1, s1)
        assertNull(registry.getPeer("m1", 1), "No seat 2 registered yet")
        registry.registerSession("m1", 2, s2)
        assertSame(registry.getPeer("m1", 1), s2, "getPeer(seat=1) should return seat 2's session")
    }

    @Test(description = "evictStale removes old match entries")
    fun evictStaleRemovesOld() {
        val registry = MatchRegistry()
        registry.getOrCreateBridge("old-match") { GameBridge() }
        registry.getOrCreateBridge("current") { GameBridge() }
        val evicted = registry.evictStale("current")
        assertEquals(evicted.size, 1)
    }
}
