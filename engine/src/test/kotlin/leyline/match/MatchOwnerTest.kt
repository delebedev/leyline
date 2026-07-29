package leyline.match

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import leyline.UnitTag
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

class MatchOwnerTest :
    FunSpec({
        tags(UnitTag)

        test("registry shares one owner across a match") {
            val registry = MatchRegistry()

            registry.ownerFor("match") shouldBeSameInstanceAs registry.ownerFor("match")
        }

        test("close cancels queued work without running it") {
            val owner = MatchOwner("terminal")
            val currentStarted = CountDownLatch(1)
            val releaseCurrent = CountDownLatch(1)
            val queuedStarted = CountDownLatch(1)
            val queuedRan = AtomicBoolean(false)
            val queuedFailure = AtomicReference<Throwable>()
            val queuedThread = AtomicReference<Thread>()
            val entrants = Executors.newFixedThreadPool(2)

            try {
                val current =
                    entrants.submit {
                        owner.reduce {
                            currentStarted.countDown()
                            releaseCurrent.await()
                        }
                    }
                currentStarted.await(2, TimeUnit.SECONDS) shouldBe true

                val queued =
                    entrants.submit {
                        queuedThread.set(Thread.currentThread())
                        queuedStarted.countDown()
                        queuedFailure.set(
                            runCatching {
                                owner.reduce { queuedRan.set(true) }
                            }.exceptionOrNull(),
                        )
                    }
                queuedStarted.await(2, TimeUnit.SECONDS) shouldBe true
                eventually(2.seconds) {
                    queuedThread.get()?.state shouldBe Thread.State.WAITING
                }

                owner.close()
                releaseCurrent.countDown()
                current.get(2, TimeUnit.SECONDS)
                queued.get(2, TimeUnit.SECONDS)

                queuedRan.get() shouldBe false
                queuedFailure.get().shouldBeInstanceOf<CancellationException>()
            } finally {
                releaseCurrent.countDown()
                owner.close()
                entrants.shutdownNow()
            }
        }

        test("terminal decision is the owner's final semantic action") {
            val owner = MatchOwner("terminal-decision")
            val terminalThread = AtomicReference<String>()

            owner.close {
                owner.assertOwnerThread()
                terminalThread.set(Thread.currentThread().name)
            }
            owner.awaitTermination()

            terminalThread.get().startsWith("match-owner-terminal") shouldBe true
        }
    })
