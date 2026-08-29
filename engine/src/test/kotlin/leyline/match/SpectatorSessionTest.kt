package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.game.state.GameBridge
import leyline.infra.ListMessageSink
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.GameVariant
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

        test("pumpOnce delivers a committed game over once") {
            val reachedHook = CountDownLatch(1)
            val releaseHook = CountDownLatch(1)
            val b = GameBridge(matchId = "test-match", cardRepository = TestCardRegistry.repo)
            bridge = b
            b.startAiVsAi(
                seed = 42,
                startGameHook =
                    Runnable {
                        reachedHook.countDown()
                        releaseHook.await(5, TimeUnit.SECONDS)
                    },
            )
            reachedHook.await(5, TimeUnit.SECONDS).shouldBeTrue()

            val sink = ListMessageSink()
            val session = SpectatorSession(SeatId(1), "test-match", sink, b)
            b.cutCoordinator.publishConcession(SeatId(1))

            assertSoftly {
                session.pumpOnce().shouldBeTrue()
                session.pumpOnce().shouldBeFalse()
                sink.rawMessages shouldHaveSize 1
                sink.messages
                    .first { it.hasGameStateMessage() }
                    .gameStateMessage.gameInfo.matchID shouldBe "test-match"
            }
            releaseHook.countDown()
            session.close()
        }

        test("pumpOnce forwards AI-vs-AI playback") {
            val reachedHook = CountDownLatch(1)
            val releaseHook = CountDownLatch(1)
            val b = GameBridge(matchId = "test-match", cardRepository = TestCardRegistry.repo)
            bridge = b
            b.startAiVsAi(
                seed = 42,
                startGameHook =
                    Runnable {
                        reachedHook.countDown()
                        releaseHook.await(5, TimeUnit.SECONDS)
                    },
            )
            reachedHook.await(5, TimeUnit.SECONDS).shouldBeTrue()

            val sink = ListMessageSink()
            val session = SpectatorSession(SeatId(1), "test-match", sink, b)
            try {
                releaseHook.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (sink.messages.isEmpty() && System.nanoTime() < deadline) {
                    session.pumpOnce()
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25))
                }

                sink.messages.shouldNotBeEmpty()
                sink.messages.size shouldBe 2
            } finally {
                releaseHook.countDown()
                session.close()
            }
        }

        test("pumpOnce surfaces a main-loop completion failure") {
            val reachedStart = CountDownLatch(1)
            val releaseStart = CountDownLatch(1)
            val reachedCompletion = CountDownLatch(1)
            val failure = IllegalStateException("completion failed")
            val b = GameBridge(matchId = "test-match", cardRepository = TestCardRegistry.repo)
            bridge = b
            b.startAiVsAi(
                seed = 42,
                startGameHook =
                    Runnable {
                        reachedStart.countDown()
                        releaseStart.await(5, TimeUnit.SECONDS)
                    },
            )
            reachedStart.await(5, TimeUnit.SECONDS).shouldBeTrue()
            b.getGame()!!.phaseHandler.setMainLoopStepCompletionHook {
                reachedCompletion.countDown()
                throw failure
            }
            val session = SpectatorSession(SeatId(1), "test-match", ListMessageSink(), b)

            try {
                releaseStart.countDown()
                reachedCompletion.await(5, TimeUnit.SECONDS).shouldBeTrue()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var observed: Throwable? = null
                while (observed == null && System.nanoTime() < deadline) {
                    try {
                        b.throwIfGameLoopFailed()
                    } catch (ex: IllegalStateException) {
                        observed = ex
                    }
                    if (observed == null) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
                }

                val thrown = shouldThrow<IllegalStateException> { session.pumpOnce() }
                thrown.cause shouldBe checkNotNull(observed).cause
            } finally {
                releaseStart.countDown()
                session.close()
            }
        }

        test("projection freezes Brawl configuration after spectator game starts") {
            val reachedHook = CountDownLatch(1)
            val releaseHook = CountDownLatch(1)
            val b = GameBridge(matchId = "test-match", cardRepository = TestCardRegistry.repo)
            bridge = b
            val sink = ListMessageSink()
            val session = SpectatorSession(SeatId(1), "test-match", sink, b)

            try {
                b.startAiVsAi(
                    seed = 42,
                    variant = "brawl",
                    startGameHook =
                        Runnable {
                            reachedHook.countDown()
                            releaseHook.await(5, TimeUnit.SECONDS)
                        },
                )
                reachedHook.await(5, TimeUnit.SECONDS).shouldBeTrue()

                b.cutCoordinator.lifecycle.publishInitial(
                    SeatId(1),
                    includeStartingPlayerPrompt = false,
                )
                session.sendRealGameState(b, revealForSeat = null)

                val gsm = sink.messages.first { it.hasGameStateMessage() }.gameStateMessage
                assertSoftly {
                    gsm.gameInfo.variant shouldBe GameVariant.Brawl
                    gsm.gameInfo.hasDeckConstraintInfo().shouldBeTrue()
                    gsm.gameInfo.freeMulliganCount shouldBe 1
                }
            } finally {
                releaseHook.countDown()
                session.close()
            }
        }

        test("registering replacement spectator session closes prior pump") {
            val b = GameBridge(matchId = "test-match", cardRepository = TestCardRegistry.repo)
            bridge = b
            val registry = MatchRegistry()
            val first = SpectatorSession(SeatId(1), "test-match", ListMessageSink(), b)
            val second = SpectatorSession(SeatId(1), "test-match", ListMessageSink(), b)

            registry.registerSession("test-match", SeatId(1), first)
            registry.registerSession("test-match", SeatId(1), second)

            first.startPump()
            first.pumpOnce() shouldBe false
            second.close()
        }
    })
