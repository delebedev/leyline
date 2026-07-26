package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.TestCardRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

class SpectatorGameBridgeTest :
    FunSpec({
        tags(IntegrationTag)

        var bridge: GameBridge? = null

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        afterEach {
            bridge?.shutdown()
            bridge = null
        }

        test("AI-vs-AI start hook exposes initial spectator snapshot before main loop") {
            val reachedHook = CountDownLatch(1)
            val releaseHook = CountDownLatch(1)
            val b = GameBridge(cardRepository = TestCardRegistry.repo)
            bridge = b

            b.startAiVsAi(
                seed = 42,
                startGameHook =
                    Runnable {
                        reachedHook.countDown()
                        releaseHook.await(5, TimeUnit.SECONDS)
                    },
            )

            assertSoftly {
                reachedHook.await(5, TimeUnit.SECONDS).shouldBeTrue()
                b.seatOf(b.getPlayer(SeatId(1))) shouldBe SeatId(1)
                b.seatOf(b.getPlayer(SeatId(2))) shouldBe SeatId(2)
                b.hasPendingEngineCuts().shouldBeFalse()
            }

            val snap = GsmSnapshot.capture(b.getGame()!!, b, "test-match", 1)
            assertSoftly {
                snap.seats.map { it.seatId }.toSet() shouldBe setOf(SeatId(1), SeatId(2))
                snap.zones.values
                    .flatMap { it.contents }
                    .shouldNotBeEmpty()
            }

            releaseHook.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!b.hasPendingEngineCuts() && System.nanoTime() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
            }
            b.hasPendingEngineCuts().shouldBeTrue()
        }
    })
