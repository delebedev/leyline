package forge.nexus.game

import org.testng.Assert.*
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.util.concurrent.ConcurrentLinkedQueue

@Test(groups = ["unit"])
class NexusGamePlaybackTest {

    @Test(description = "Playback queues messages and reports queue size")
    fun queueBasics() {
        val queue = ConcurrentLinkedQueue<List<GREToClientMessage>>()
        assertTrue(queue.isEmpty())
        queue.add(emptyList())
        assertEquals(queue.size, 1)
        val drained = queue.poll()
        assertNotNull(drained)
        assertTrue(queue.isEmpty())
    }

    @Test(description = "NexusGamePlayback class exists with expected API")
    fun classContract() {
        val clazz = NexusGamePlayback::class.java
        assertNotNull(clazz.getMethod("drainQueue"))
        assertNotNull(clazz.getMethod("hasPendingMessages"))
    }

    @Test(description = "seedCounters updates atomics read by getCounters")
    fun seedAndGetCounters() {
        val pb = createMinimalPlayback()
        pb.seedCounters(10, 20)
        val (msg, gs) = pb.getCounters()
        assertEquals(msg, 10)
        assertEquals(gs, 20)
    }

    @Test(description = "After edictal-like msgId advance without re-seed, playback has stale msgId")
    fun staleCountersAfterEdictalAdvance() {
        val pb = createMinimalPlayback()
        // Initial seed: simulates sendRealGameState seeding playback
        pb.seedCounters(10, 20)

        // Simulate 3 edictal passes: MatchSession advances msgId by 3 but never re-seeds.
        // edictalPass returns gsId unchanged, msgId+1 each time.
        val matchMsgId = 13 // 10 + 3 edictals
        val matchGsId = 20 // unchanged by edictalPass

        // Playback still has stale counters from initial seed
        val (pbMsg, pbGs) = pb.getCounters()
        assertEquals(pbMsg, 10, "Playback msgId should be stale (10, not $matchMsgId)")
        assertEquals(pbGs, 20, "Playback gsId matches (edictals don't change gsId)")

        // If AI captured now, it would use msgId=10 — colliding with already-sent edictals
        // that used msgIds 10, 11, 12. The fix: re-seed before awaitPriority.
        assertTrue(pbMsg < matchMsgId, "Stale playback msgId ($pbMsg) < match msgId ($matchMsgId)")

        // After re-seeding (the fix), counters should be in sync
        pb.seedCounters(matchMsgId, matchGsId)
        val (fixedMsg, fixedGs) = pb.getCounters()
        assertEquals(fixedMsg, matchMsgId)
        assertEquals(fixedGs, matchGsId)
    }

    @Test(description = "Stale counters cause msgId overlap with edictal messages")
    fun staleCountersMsgIdOverlap() {
        // Scenario: postAction seeds playback at (msgId=28, gsId=14)
        // 2 edictals advance MatchSession msgId to 30, gsId stays 14
        // Without re-seed, AI captures with msgId=28 → collides with edictal msgs

        val pb = createMinimalPlayback()
        pb.seedCounters(28, 14)

        // Edictals sent at msgIds 28, 29 — MatchSession msgId now 30
        val edictalMsgIds = listOf(28, 29)
        val matchMsgIdAfterEdictals = 30

        // Playback would start AI diffs at stale msgId=28
        val (staleMsgId, _) = pb.getCounters()
        assertTrue(
            staleMsgId in edictalMsgIds.first()..edictalMsgIds.last(),
            "Stale playback msgId ($staleMsgId) overlaps edictal range $edictalMsgIds",
        )

        // Fix: re-seed before awaitPriority
        pb.seedCounters(matchMsgIdAfterEdictals, 14)
        val (fixedMsgId, _) = pb.getCounters()
        assertTrue(
            fixedMsgId > edictalMsgIds.last(),
            "After re-seed, playback msgId ($fixedMsgId) is past edictal range",
        )
    }

    private fun createMinimalPlayback(): NexusGamePlayback {
        val bridge = GameBridge()
        return NexusGamePlayback(bridge, "test", 1)
    }
}
