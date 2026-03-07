package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag

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
class GamePlaybackCounterTest :
    FunSpec({

        tags(UnitTag)

        test("MessageCounter nextGsId increments atomically") {
            val counter = MessageCounter(initialGsId = 10, initialMsgId = 1)
            counter.nextGsId() shouldBe 11
            counter.nextGsId() shouldBe 12
            counter.currentGsId() shouldBe 12
        }

        test("MessageCounter nextMsgId increments atomically") {
            val counter = MessageCounter(initialGsId = 0, initialMsgId = 5)
            counter.nextMsgId() shouldBe 6
            counter.nextMsgId() shouldBe 7
            counter.currentMsgId() shouldBe 7
        }

        test("Concurrent access produces unique IDs") {
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
            all.size shouldBe iterations * 2
            all.toSet().size shouldBe all.size
            counter.currentGsId() shouldBe iterations * 2
        }

        test("setGsId and setMsgId work for handshake setup") {
            val counter = MessageCounter()
            counter.setGsId(42)
            counter.setMsgId(99)
            counter.currentGsId() shouldBe 42
            counter.currentMsgId() shouldBe 99
            counter.nextGsId() shouldBe 43
            counter.nextMsgId() shouldBe 100
        }

        test("GamePlayback uses shared counter (no local atomics)") {
            val counter = MessageCounter(initialGsId = 10, initialMsgId = 20)
            val bridge = GameBridge(messageCounter = counter)
            val playback = GamePlayback(bridge, "test", 1, counter)

            playback.drainQueue().shouldBeEmpty()
            playback.hasPendingMessages().shouldBeFalse()

            counter.nextGsId() shouldBe 11
            counter.currentGsId() shouldBe 11
        }
    })
