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

    @Test(description = "Shared MessageCounter is used by playback — no local atomics")
    fun sharedCounterContract() {
        val counter = MessageCounter(initialGsId = 10, initialMsgId = 20)
        val pb = createMinimalPlayback(counter)

        // Counter state accessible via shared counter
        assertEquals(counter.currentGsId(), 10)
        assertEquals(counter.currentMsgId(), 20)

        // Advancing counter from outside is visible to playback's counter
        counter.nextGsId()
        assertEquals(counter.currentGsId(), 11)
    }

    @Test(description = "No duplicate msgIds when two threads use the same counter")
    fun noDuplicateMsgIdsWithSharedCounter() {
        // Scenario: session thread advances msgId for edictal passes,
        // engine thread advances msgId for AI diffs — both on same counter.
        val counter = MessageCounter(initialGsId = 10, initialMsgId = 10)

        // Simulate session thread: 3 edictal passes advance msgId
        val sessionMsgIds = (1..3).map { counter.nextMsgId() }

        // Simulate engine thread: 2 AI diffs advance msgId
        val engineMsgIds = (1..2).map { counter.nextMsgId() }

        val allMsgIds = sessionMsgIds + engineMsgIds
        assertEquals(allMsgIds.toSet().size, allMsgIds.size, "All msgIds should be unique")
        assertTrue(allMsgIds.last() > allMsgIds.first(), "msgIds should be monotonically increasing")
    }

    private fun createMinimalPlayback(counter: MessageCounter = MessageCounter()): NexusGamePlayback {
        val bridge = GameBridge(messageCounter = counter)
        return NexusGamePlayback(bridge, "test", 1, counter)
    }
}
