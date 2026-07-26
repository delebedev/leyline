package leyline.game

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class EngineCutQueueTest :
    FunSpec({
        tags(UnitTag)

        test("acknowledgement through checkpoint preserves FIFO and leaves suffix") {
            val queue = EngineCutQueue()
            queue.beginGeneration()
            val actionCheckpoint = queue.publishReady(InteractionReadiness.ACTION)
            val checkpoint = queue.publishReady(InteractionReadiness.PROMPT)
            queue.publishReady(InteractionReadiness.NUMERIC_INPUT)

            val first = checkNotNull(queue.peekThrough(checkpoint))
            (first as EngineCut.InteractionReady).kind shouldBe InteractionReadiness.ACTION
            first.checkpoint shouldBe actionCheckpoint
            queue.acknowledge(first)
            val second = checkNotNull(queue.peekThrough(checkpoint))
            (second as EngineCut.InteractionReady).kind shouldBe InteractionReadiness.PROMPT
            queue.acknowledge(second)
            queue.peekThrough(checkpoint).shouldBeNull()
            val suffix = checkNotNull(queue.peekThrough(queue.latestCheckpoint()))
            (suffix as EngineCut.InteractionReady).kind shouldBe InteractionReadiness.NUMERIC_INPUT
        }

        test("unacknowledged cut remains at the head") {
            val queue = EngineCutQueue()
            queue.beginGeneration()
            val checkpoint = queue.publishReady(InteractionReadiness.ACTION)
            queue.publishReady(InteractionReadiness.PROMPT)

            val firstAttempt = checkNotNull(queue.peekThrough(checkpoint))
            queue.peekThrough(queue.latestCheckpoint()) shouldBe firstAttempt
        }

        test("generation replacement rejects an old checkpoint") {
            val queue = EngineCutQueue()
            queue.beginGeneration()
            val old = queue.publishReady(InteractionReadiness.ACTION)
            queue.beginGeneration()

            shouldThrow<IllegalStateException> {
                queue.peekThrough(old)
            }
        }
    })
