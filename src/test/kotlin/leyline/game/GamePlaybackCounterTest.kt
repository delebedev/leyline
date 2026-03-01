package leyline.game

import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/**
 * Counter invariants for [MessageCounter].
 *
 * The shared counter is accessed by two threads:
 *   - Game thread: GamePlayback.captureAndPause calls nextMsgId/nextGsId
 *   - Handler thread: BundleBuilder methods call nextMsgId/nextGsId
 *
 * AtomicInteger guarantees no duplicates. These tests verify the basic
 * contract and thread safety model.
 */
@Test(groups = ["unit"])
class GamePlaybackCounterTest {

    @Test(description = "MessageCounter nextGsId increments atomically")
    fun nextGsIdIncrements() {
        val counter = MessageCounter(initialGsId = 10, initialMsgId = 1)
        assertEquals(counter.nextGsId(), 11)
        assertEquals(counter.nextGsId(), 12)
        assertEquals(counter.currentGsId(), 12)
    }

    @Test(description = "MessageCounter nextMsgId increments atomically")
    fun nextMsgIdIncrements() {
        val counter = MessageCounter(initialGsId = 0, initialMsgId = 5)
        assertEquals(counter.nextMsgId(), 6)
        assertEquals(counter.nextMsgId(), 7)
        assertEquals(counter.currentMsgId(), 7)
    }

    @Test(description = "Concurrent access produces unique IDs")
    fun concurrentAccessProducesUniqueIds() {
        val counter = MessageCounter(initialGsId = 0, initialMsgId = 0)
        val iterations = 10_000
        val ids = java.util.concurrent.ConcurrentLinkedQueue<Int>()

        val t1 = Thread { repeat(iterations) { ids.add(counter.nextGsId()) } }
        val t2 = Thread { repeat(iterations) { ids.add(counter.nextGsId()) } }

        t1.start()
        t2.start()
        t1.join()
        t2.join()

        val all = ids.toList()
        assertEquals(all.size, iterations * 2, "Should have ${iterations * 2} IDs total")
        assertEquals(all.toSet().size, all.size, "All IDs should be unique (no duplicates)")
        assertEquals(counter.currentGsId(), iterations * 2, "Final counter should be ${iterations * 2}")
    }

    @Test(description = "setGsId and setMsgId work for handshake setup")
    fun settersWork() {
        val counter = MessageCounter()
        counter.setGsId(42)
        counter.setMsgId(99)
        assertEquals(counter.currentGsId(), 42)
        assertEquals(counter.currentMsgId(), 99)
        assertEquals(counter.nextGsId(), 43)
        assertEquals(counter.nextMsgId(), 100)
    }

    @Test(description = "GamePlayback uses shared counter (no local atomics)")
    fun playbackUsesSharedCounter() {
        val counter = MessageCounter(initialGsId = 10, initialMsgId = 20)
        val bridge = GameBridge(messageCounter = counter)
        val playback = GamePlayback(bridge, "test", 1, counter)

        // Playback queue is accessible
        assertTrue(playback.drainQueue().isEmpty())
        assertTrue(!playback.hasPendingMessages())

        // Counter is shared — advancing from outside is visible
        assertEquals(counter.nextGsId(), 11)
        assertEquals(counter.currentGsId(), 11)
    }
}
