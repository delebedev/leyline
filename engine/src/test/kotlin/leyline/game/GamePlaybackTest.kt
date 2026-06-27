package leyline.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.bundle.MessageCounter
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.*
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.util.concurrent.ConcurrentLinkedQueue

class GamePlaybackTest :
    FunSpec({

        tags(UnitTag)

        fun createMinimalPlayback(counter: MessageCounter = MessageCounter()): GamePlayback {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository(), messageCounter = counter)
            return GamePlayback(bridge, "test", 1, counter)
        }

        fun gameStateMessage(
            msgId: Int,
            gsId: Int,
        ): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setMsgId(msgId)
                .setGameStateId(gsId)
                .addSystemSeatIds(1)
                .setGameStateMessage(
                    GameStateMessage
                        .newBuilder()
                        .setType(GameStateType.Diff)
                        .setGameStateId(gsId)
                        .setPrevGameStateId((gsId - 1).coerceAtLeast(0)),
                ).build()

        @Suppress("UNCHECKED_CAST")
        fun playbackQueue(playback: GamePlayback): ConcurrentLinkedQueue<List<GREToClientMessage>> {
            val field = GamePlayback::class.java.getDeclaredField("queue")
            field.isAccessible = true
            return field.get(playback) as ConcurrentLinkedQueue<List<GREToClientMessage>>
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

        test("drain before outbound respects older queued gameStateId") {
            val playback = createMinimalPlayback()
            val olderGsLaterMsg = listOf(gameStateMessage(msgId = 757, gsId = 576))
            val newerGsLaterMsg = listOf(gameStateMessage(msgId = 760, gsId = 579))
            playbackQueue(playback).add(olderGsLaterMsg)
            playbackQueue(playback).add(newerGsLaterMsg)

            playback.drainQueueBeforeMsgId(msgId = 755, maxGsId = 578) shouldBe listOf(olderGsLaterMsg)
            playback.drainQueue() shouldBe listOf(newerGsLaterMsg)
        }

        test("drain before outbound leaves future gameStateId queued") {
            val playback = createMinimalPlayback()
            val futureGsEarlierMsg = listOf(gameStateMessage(msgId = 747, gsId = 569))
            playbackQueue(playback).add(futureGsEarlierMsg)

            playback.drainQueueBeforeMsgId(msgId = 749, maxGsId = 568).shouldBeEmpty()
            playback.drainQueue() shouldBe listOf(futureGsEarlierMsg)
        }
    })
