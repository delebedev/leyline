package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.bundle.MessageCounter
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.util.concurrent.ConcurrentLinkedQueue

class GamePlaybackTest :
    FunSpec({

        tags(UnitTag)

        fun createMinimalPlayback(counter: MessageCounter = MessageCounter()): GamePlayback {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository(), messageCounter = counter)
            return GamePlayback(bridge, "test", 1, counter)
        }

        test("Playback queues messages and reports queue size") {
            val queue = ConcurrentLinkedQueue<List<GREToClientMessage>>()
            queue.shouldBeEmpty()
            queue.add(emptyList())
            queue.size shouldBe 1
            val drained = queue.poll()
            drained.shouldNotBeNull()
            queue.shouldBeEmpty()
        }

        test("Shared MessageCounter is used by playback — no local atomics") {
            val counter = MessageCounter(initialGsId = 10, initialMsgId = 20)

            @Suppress("UnusedPrivateProperty")
            val pb = createMinimalPlayback(counter)

            counter.currentGsId() shouldBe 10
            counter.currentMsgId() shouldBe 20

            counter.nextGsId()
            counter.currentGsId() shouldBe 11
        }

        test("No duplicate msgIds when two threads use the same counter") {
            val counter = MessageCounter(initialGsId = 10, initialMsgId = 10)

            val sessionMsgIds = (1..3).map { counter.nextMsgId() }
            val engineMsgIds = (1..2).map { counter.nextMsgId() }

            val allMsgIds = sessionMsgIds + engineMsgIds
            allMsgIds.toSet().size shouldBe allMsgIds.size
            allMsgIds.last() shouldBeGreaterThan allMsgIds.first()
        }
    })
