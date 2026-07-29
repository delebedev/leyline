package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.coord.EngineWorkerExit
import leyline.bridge.coord.EngineWorkerStop
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.state.GameBridge
import leyline.infra.MatchOutput
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class MatchWorkerSupervisorTest :
    FunSpec({
        tags(UnitTag)

        fun stubBridge(): GameBridge =
            GameBridge(cardRepository = InMemoryCardRepository()).also {
                it.wrapGame(GameBootstrap.createGame())
            }

        test("buffered engine failure converges after registry publication") {
            val registry = MatchRegistry()
            val bridge = stubBridge()
            var match: Match? = null

            shouldThrow<IllegalStateException> {
                registry.getOrCreateMatch("failed-start") {
                    Match("failed-start", bridge).also {
                        match = it
                        bridge.publishWorkerExit(
                            EngineWorkerExit.Failed(
                                failureType = "test.WorkerFailure",
                                message = "failed during start",
                            ),
                        )
                    }
                }
            }.message shouldBe "Match worker failed during startup"

            assertSoftly {
                match?.state shouldBe MatchState.FINISHED
                registry.getMatch("failed-start").shouldBeNull()
                registry.getConnection("failed-start", SeatId(1)).shouldBeNull()
            }
        }

        test("engine failure closes transports and removes the failed generation") {
            val registry = MatchRegistry()
            val bridge = stubBridge()
            val match = registry.getOrCreateMatch("failed-worker") { Match("failed-worker", bridge) }
            val outputClosed = AtomicBoolean(false)
            val connection =
                MatchConnection(
                    registry = registry,
                    output =
                        object : MatchOutput {
                            override fun send(message: MatchServiceToClientMessage) = Unit

                            override fun close() {
                                outputClosed.set(true)
                            }
                        },
                    cardRepository = TestCardRegistry.repo,
                )
            registry.registerConnection("failed-worker", SeatId(1), connection)

            bridge.publishWorkerExit(
                EngineWorkerExit.Failed(
                    failureType = "test.WorkerFailure",
                    message = "worker failed",
                ),
            )

            assertSoftly {
                outputClosed.get() shouldBe true
                match.state shouldBe MatchState.FINISHED
                registry.getMatch("failed-worker").shouldBeNull()
                registry.getConnection("failed-worker", SeatId(1)).shouldBeNull()
            }
        }

        test("engine failure releases an owner waiting for readiness") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val waiter = Executors.newSingleThreadExecutor()
            try {
                val result = waiter.submit { bridge.awaitPriorityCut(10_000) }

                bridge.publishWorkerExit(
                    EngineWorkerExit.Failed(
                        failureType = "test.WorkerFailure",
                        message = "worker failed",
                    ),
                )

                result.get(1, TimeUnit.SECONDS).shouldBeNull()
            } finally {
                waiter.shutdownNow()
            }
        }

        test("repeated stop before startup stays not running") {
            val supervisor = MatchWorkerSupervisor(stubBridge(), stopWaitTimeoutMs = 10)

            supervisor.stop() shouldBe EngineWorkerStop.NotRunning
            supervisor.stop() shouldBe EngineWorkerStop.NotRunning
        }

        test("stop during startup cancels the installed worker before teardown returns") {
            val bridge = stubBridge()
            val workerReady = CountDownLatch(1)
            val releaseStart = CountDownLatch(1)
            val stopCalled = CountDownLatch(1)
            val supervisor =
                MatchWorkerSupervisor(
                    bridge = bridge,
                    stopWorker = {
                        stopCalled.countDown()
                        EngineWorkerStop.Stopped
                    },
                    stopWaitTimeoutMs = 1_000,
                )
            val entrants = Executors.newFixedThreadPool(2)

            try {
                val start =
                    entrants.submit<Boolean> {
                        supervisor.startWorker { continueAfterWorkerReady ->
                            workerReady.countDown()
                            releaseStart.await()
                            continueAfterWorkerReady()
                        }
                    }
                workerReady.await(1, TimeUnit.SECONDS) shouldBe true

                val stop = entrants.submit<EngineWorkerStop> { supervisor.stop() }
                eventually(1.seconds) {
                    supervisor.isStopRequested() shouldBe true
                }
                releaseStart.countDown()

                assertSoftly {
                    stop.get(1, TimeUnit.SECONDS) shouldBe EngineWorkerStop.Stopped
                    stopCalled.await(1, TimeUnit.SECONDS) shouldBe true
                    start.get(1, TimeUnit.SECONDS) shouldBe false
                }
            } finally {
                releaseStart.countDown()
                entrants.shutdownNow()
            }
        }

        test("disconnect racing engine failure converges once") {
            val registry = MatchRegistry()
            val bridge = stubBridge()
            val match = registry.getOrCreateMatch("failure-race") { Match("failure-race", bridge) }
            val transitions = AtomicInteger()
            match.onStateChanged = { if (it == MatchState.FINISHED) transitions.incrementAndGet() }
            val connection =
                MatchConnection(
                    registry = registry,
                    output =
                        object : MatchOutput {
                            override fun send(message: MatchServiceToClientMessage) = Unit

                            override fun close() = Unit
                        },
                    cardRepository = TestCardRegistry.repo,
                )
            registry.registerConnection("failure-race", SeatId(1), connection)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val entrants = Executors.newFixedThreadPool(2)

            try {
                val disconnect =
                    entrants.submit {
                        ready.countDown()
                        start.await()
                        registry.teardownMatch(
                            matchId = "failure-race",
                            reason = MatchTeardownReason.Disconnect,
                            seatId = SeatId(1),
                            expectedConnection = connection,
                        )
                    }
                val failure =
                    entrants.submit {
                        ready.countDown()
                        start.await()
                        bridge.publishWorkerExit(
                            EngineWorkerExit.Failed(
                                failureType = "test.WorkerFailure",
                                message = "worker failed",
                            ),
                        )
                    }
                ready.await(1, TimeUnit.SECONDS) shouldBe true
                start.countDown()
                disconnect.get(2, TimeUnit.SECONDS)
                failure.get(2, TimeUnit.SECONDS)

                assertSoftly {
                    transitions.get() shouldBe 1
                    match.state shouldBe MatchState.FINISHED
                    registry.getMatch("failure-race").shouldBeNull()
                    registry.getConnection("failure-race", SeatId(1)).shouldBeNull()
                }
            } finally {
                start.countDown()
                entrants.shutdownNow()
            }
        }

        test("stale match generation cannot publish a connection") {
            val registry = MatchRegistry()
            val oldBridge = stubBridge()
            val oldMatch = registry.getOrCreateMatch("generation") { Match("generation", oldBridge) }
            registry.teardownMatch("generation", MatchTeardownReason.Disconnect)
            registry.getOrCreateMatch("generation") { Match("generation", stubBridge()) }
            val session =
                MatchSession(
                    ConnectionState(SeatId(1), "generation", leyline.infra.ListMessageSink(), registry),
                    oldBridge,
                    paceDelayMs = 0,
                )
            val connection =
                MatchConnection(
                    registry = registry,
                    output =
                        object : MatchOutput {
                            override fun send(message: MatchServiceToClientMessage) = Unit

                            override fun close() = Unit
                        },
                    cardRepository = TestCardRegistry.repo,
                )

            shouldThrow<IllegalStateException> {
                registry.publishSessionAndConnection(
                    "generation",
                    oldMatch,
                    SeatId(1),
                    session,
                    connection,
                ) {}
            }.message shouldBe "Match generation is no longer active"
        }
    })
