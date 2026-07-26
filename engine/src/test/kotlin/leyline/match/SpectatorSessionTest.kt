package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.game.InteractionReadiness
import leyline.game.seedDiffBaseline
import leyline.game.state.GameBridge
import leyline.infra.ListMessageSink
import leyline.testkit.TestCardRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

class SpectatorSessionTest :
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

        fun waitUntil(
            owner: MatchOwner,
            predicate: () -> Boolean,
        ) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!owner.reduce(predicate) && System.nanoTime() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
            }
            owner.reduce(predicate).shouldBeTrue()
        }

        test("owner sends game over once for duplicate terminal readiness") {
            val reachedHook = CountDownLatch(1)
            val releaseHook = CountDownLatch(1)
            val b = GameBridge(cardRepository = TestCardRegistry.repo)
            bridge = b
            val owner = MatchOwner("test-match")
            val sink = ListMessageSink()
            val session = SpectatorSession(SeatId(1), "test-match", sink, b, owner = owner)
            session.startObserving()
            b.startAiVsAi(
                seed = 42,
                startGameHook =
                    Runnable {
                        reachedHook.countDown()
                        releaseHook.await(5, TimeUnit.SECONDS)
                    },
            )
            reachedHook.await(5, TimeUnit.SECONDS).shouldBeTrue()

            b.publishEngineReady(InteractionReadiness.GAME_OVER)
            b.publishEngineReady(InteractionReadiness.GAME_OVER)
            waitUntil(owner) { sink.rawMessages.isNotEmpty() }

            assertSoftly {
                sink.rawMessages shouldHaveSize 1
                sink.messages.shouldNotBeEmpty()
            }
            releaseHook.countDown()
            session.close()
            owner.close()
            owner.awaitTermination()
        }

        test("owner forwards AI-vs-AI observations in sequence") {
            val reachedHook = CountDownLatch(1)
            val releaseHook = CountDownLatch(1)
            val b = GameBridge(cardRepository = TestCardRegistry.repo)
            bridge = b
            val owner = MatchOwner("test-match")
            val sink = ListMessageSink()
            val session = SpectatorSession(SeatId(1), "test-match", sink, b, owner = owner)
            session.startObserving()
            b.startAiVsAi(
                seed = 42,
                startGameHook =
                    Runnable {
                        reachedHook.countDown()
                        releaseHook.await(5, TimeUnit.SECONDS)
                    },
            )
            reachedHook.await(5, TimeUnit.SECONDS).shouldBeTrue()

            try {
                val initialGsId = session.counter.nextGsId()
                b.seedDiffBaseline(checkNotNull(b.getGame()), initialGsId)
                releaseHook.countDown()
                waitUntil(owner) { sink.messages.isNotEmpty() }

                sink.messages.shouldNotBeEmpty()
                sink.messages.all { it.hasGameStateMessage() }.shouldBeTrue()
                val messageIds = sink.messages.map { it.msgId }
                messageIds shouldBe messageIds.sorted().distinct()
            } finally {
                releaseHook.countDown()
                session.close()
                owner.close()
                owner.awaitTermination()
            }
        }

        test("replacement spectator fences queued delivery from prior session") {
            val b = GameBridge(cardRepository = TestCardRegistry.repo)
            bridge = b
            val registry = MatchRegistry()
            val owner = registry.ownerFor("test-match")
            val firstSink = ListMessageSink()
            val secondSink = ListMessageSink()
            val first = SpectatorSession(SeatId(1), "test-match", firstSink, b, owner = owner)
            val second = SpectatorSession(SeatId(1), "test-match", secondSink, b, owner = owner)
            val reachedHook = CountDownLatch(1)
            val releaseHook = CountDownLatch(1)

            registry.registerSession("test-match", SeatId(1), first)
            first.startObserving()
            b.startAiVsAi(
                seed = 42,
                startGameHook =
                    Runnable {
                        reachedHook.countDown()
                        releaseHook.await(5, TimeUnit.SECONDS)
                    },
            )
            reachedHook.await(5, TimeUnit.SECONDS).shouldBeTrue()
            val initialGsId = second.counter.nextGsId()
            b.seedDiffBaseline(checkNotNull(b.getGame()), initialGsId)

            registry.registerSession("test-match", SeatId(1), second)
            second.startObserving()
            second.reconcilePendingEngineCuts()
            releaseHook.countDown()
            waitUntil(owner) { secondSink.messages.isNotEmpty() }

            firstSink.messages shouldHaveSize 0
            secondSink.messages.shouldNotBeEmpty()
            second.close()
            owner.close()
            owner.awaitTermination()
        }
    })
