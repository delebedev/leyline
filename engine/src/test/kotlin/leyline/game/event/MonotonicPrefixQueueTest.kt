package leyline.game.event

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class MonotonicPrefixQueueTest :
    FunSpec({
        tags(UnitTag)

        test("commit consumes only the reserved prefix") {
            val queue = MonotonicPrefixQueue<String>()
            queue.add("first")
            val reservation = queue.reserve()
            queue.add("later")

            queue.consume(reservation)

            queue.toList() shouldBe listOf("later")
        }

        test("consecutive reservations own disjoint FIFO prefixes") {
            val queue = MonotonicPrefixQueue<String>()
            queue.add("first")
            val first = queue.reserve()
            queue.add("second")
            val second = queue.reserve()

            first.values shouldBe listOf("first")
            second.values shouldBe listOf("second")
            queue.consume(first)
            queue.consume(second)

            queue.toList() shouldBe emptyList()
        }

        test("equal values cannot recreate a consumed prefix") {
            val queue = MonotonicPrefixQueue<String>()
            queue.add("same")
            val reservation = queue.reserve()
            queue.consume(reservation)
            queue.add("same")

            shouldThrow<IllegalStateException> {
                queue.validate(reservation)
            }
        }

        test("release makes the exact prefix reservable again and retains a later suffix") {
            val queue = MonotonicPrefixQueue<String>()
            queue.add("first")
            val failed = queue.reserve()

            queue.release(failed)
            val retry = queue.reserve()
            queue.add("later")

            retry.values shouldBe listOf("first")
            queue.consume(retry)
            queue.toList() shouldBe listOf("later")
        }

        test("released earlier prefix can retry ahead of an existing later reservation") {
            val queue = MonotonicPrefixQueue<String>()
            queue.add("first")
            val failed = queue.reserve()
            queue.add("second")
            val later = queue.reserve()

            queue.release(failed)
            val retry = queue.reserve()

            retry.values shouldBe listOf("first")
            later.values shouldBe listOf("second")
            queue.consume(retry)
            queue.consume(later)
            queue.toList() shouldBe emptyList()
        }

        test("reset invalidates even an empty reservation") {
            val queue = MonotonicPrefixQueue<String>()
            val reservation = queue.reserve()

            queue.clear()

            shouldThrow<IllegalStateException> {
                queue.validate(reservation)
            }
        }
    })
