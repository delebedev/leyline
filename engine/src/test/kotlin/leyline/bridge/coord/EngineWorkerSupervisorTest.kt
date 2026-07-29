package leyline.bridge.coord

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.UnitTag
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

class EngineWorkerSupervisorTest :
    FunSpec({
        tags(UnitTag)

        test("publishes completed exit as an immutable value") {
            val exits = LinkedBlockingQueue<EngineWorkerExit>()
            val runningDuringExit = AtomicReference<Boolean>()
            lateinit var supervisor: EngineWorkerSupervisor
            supervisor =
                EngineWorkerSupervisor { exit ->
                    runningDuringExit.set(supervisor.isRunning)
                    exits.add(exit)
                }

            supervisor.start("worker-completed") {}

            assertSoftly {
                exits.poll(1, TimeUnit.SECONDS) shouldBe EngineWorkerExit.Completed
                runningDuringExit.get() shouldBe true
            }
            eventually(1.seconds) {
                supervisor.isRunning shouldBe false
            }
        }

        test("publishes failure facts without retaining the throwable") {
            val exits = LinkedBlockingQueue<EngineWorkerExit>()
            val supervisor = EngineWorkerSupervisor(onExit = exits::add)

            supervisor.start("worker-failed") {
                error("broken worker")
            }

            val failure = exits.poll(1, TimeUnit.SECONDS).shouldBeInstanceOf<EngineWorkerExit.Failed>()
            failure.failureType shouldBe IllegalStateException::class.java.name
            failure.message shouldBe "broken worker"
        }

        test("cooperative stop cancels pending work and joins the worker") {
            val exits = LinkedBlockingQueue<EngineWorkerExit>()
            val entered = CountDownLatch(1)
            val supervisor = EngineWorkerSupervisor(onExit = exits::add)
            supervisor.start("worker-cancelled") {
                entered.countDown()
                CountDownLatch(1).await()
            }
            assertSoftly {
                entered.await(1, TimeUnit.SECONDS) shouldBe true
                supervisor.stop {} shouldBe EngineWorkerStop.Stopped
                exits.poll(1, TimeUnit.SECONDS) shouldBe EngineWorkerExit.Cancelled
                supervisor.isRunning shouldBe false
            }
        }

        test("timed out stop retains the live worker until its eventual exit") {
            val exits = LinkedBlockingQueue<EngineWorkerExit>()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val supervisor = EngineWorkerSupervisor(joinTimeoutMs = 10, onExit = exits::add)
            supervisor.start("worker-timeout") {
                entered.countDown()
                while (release.count > 0) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        // Deliberately model an engine call that has not reached a cancellation point.
                    }
                }
            }
            assertSoftly {
                entered.await(1, TimeUnit.SECONDS) shouldBe true
                supervisor.stop {} shouldBe EngineWorkerStop.TimedOut
                supervisor.isRunning shouldBe true
            }

            release.countDown()
            eventually(1.seconds) {
                exits.poll() shouldBe EngineWorkerExit.Cancelled
                supervisor.isRunning shouldBe false
            }
        }
    })
