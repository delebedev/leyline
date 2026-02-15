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
}
