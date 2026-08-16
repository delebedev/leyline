package leyline.bridge.coord

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private data class Probe(
    val name: String,
    val reply: CompletableFuture<String> = CompletableFuture(),
)

/**
 * Command arbitration and delivery mechanics shared by the targeting and
 * mana-source payment windows. Their suites keep the domain outcomes — legality,
 * payment facts, exact handles, protocol envelopes — and this one owns the races.
 */
class InteractiveCommandExchangeTest :
    FunSpec({
        tags(UnitTag)

        fun exchange(deadlineNanos: Long? = null): InteractiveCommandExchange<Probe, String> {
            val tokens = AtomicLong()
            return InteractiveCommandExchange(deadlineNanos, tokens::incrementAndGet, Probe::reply)
        }

        test("an admitted command becomes in flight and its reply reaches the submitter") {
            val exchange = exchange()
            val command = Probe("toggle")
            exchange.inFlight.shouldBeNull()

            exchange.admitLocked(command)
            exchange.inFlight shouldBe command

            val taken = exchange.next { error("no deadline") }
            taken shouldBe command
            command.reply.complete("accepted")
            exchange.awaitReply(command) shouldBe "accepted"
        }

        test("a passed deadline hands arbitration to the owner") {
            val exchange = exchange(deadlineNanos = System.nanoTime() - 1)

            assertSoftly {
                exchange.queuedLocked() shouldBe false
                exchange.next { Probe("timed-out") }.name shouldBe "timed-out"
            }
        }

        test("a command that arrives while the deadline is claimed still reaches the engine") {
            val exchange = exchange(deadlineNanos = System.nanoTime() - 1)
            val late = Probe("late")
            exchange.admitLocked(late)

            val taken =
                exchange.next {
                    check(exchange.queuedLocked())
                    checkNotNull(exchange.pollQueuedLocked())
                }

            taken shouldBe late
        }

        test("only the exact delivery token acknowledges, and tokens stay monotonic") {
            val exchange = exchange()
            val first = exchange.beginDeliveryLocked()
            exchange.clearDeliveryLocked()
            val second = exchange.beginDeliveryLocked()

            assertSoftly {
                second.token shouldBe first.token + 1
                exchange.acknowledgeLocked(first.token).shouldBeNull()
                exchange.acknowledgeLocked(second.token).shouldNotBeNull()
                second.acknowledged.isDone shouldBe true
            }
        }

        test("release wakes the acknowledging thread and clearing retires the command") {
            val exchange = exchange()
            exchange.admitLocked(Probe("select"))
            val delivery = exchange.beginDeliveryLocked()
            val released = CountDownLatch(1)
            Thread {
                delivery.awaitRelease()
                released.countDown()
            }.start()

            released.await(100, TimeUnit.MILLISECONDS) shouldBe false
            delivery.released.complete(Unit)
            released.await(3, TimeUnit.SECONDS) shouldBe true

            exchange.clearDeliveryLocked()
            assertSoftly {
                exchange.delivery.shouldBeNull()
                exchange.inFlight.shouldBeNull()
            }
        }

        test("termination fails the taken command and its delivery, then wakes the engine") {
            val exchange = exchange()
            val taken = Probe("in-flight")
            exchange.admitLocked(taken)
            exchange.next { error("no deadline") } shouldBe taken
            val delivery = exchange.beginDeliveryLocked()
            val cause = IllegalStateException("match closed")

            exchange.terminateLocked(cause, Probe("terminal"))

            assertSoftly {
                shouldThrow<IllegalStateException> { exchange.awaitReply(taken) } shouldBe cause
                shouldThrow<IllegalStateException> { delivery.awaitAcknowledgement() } shouldBe cause
                shouldThrow<IllegalStateException> { delivery.awaitRelease() } shouldBe cause
                exchange.next { error("terminal command is queued") }.name shouldBe "terminal"
            }
        }

        test("termination fails a command still waiting in the queue") {
            val exchange = exchange()
            val waiting = Probe("waiting")
            exchange.admitLocked(waiting)
            val cause = IllegalStateException("match closed")

            exchange.terminateLocked(cause, Probe("terminal"))

            assertSoftly {
                shouldThrow<IllegalStateException> { exchange.awaitReply(waiting) } shouldBe cause
                exchange.next { error("terminal command is queued") } shouldBe waiting
                exchange.next { error("terminal command is queued") }.name shouldBe "terminal"
            }
        }

        test("a failed reply reaches the submitter unwrapped") {
            val exchange = exchange()
            val command = Probe("cancel")
            val cause = IllegalArgumentException("rejected")
            val thrown = AtomicReference<Throwable>()
            val finished = CountDownLatch(1)
            exchange.admitLocked(command)
            Thread {
                runCatching { exchange.awaitReply(command) }.onFailure(thrown::set)
                finished.countDown()
            }.start()

            command.reply.completeExceptionally(cause)

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                thrown.get() shouldBe cause
            }
        }
    })
