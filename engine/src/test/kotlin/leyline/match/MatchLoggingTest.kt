package leyline.match

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.domain.service.MatchCoordinator
import leyline.game.PlaybackTerminalFailure
import leyline.game.state.GameBridge
import leyline.game.state.ProjectionViewer
import leyline.game.state.ProjectionViewerRole
import leyline.infra.MessageSink
import leyline.testkit.BoardTest
import leyline.testkit.IsolatedBoardLifecycle
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MatchLoggingTest :
    BoardTest({
        test("concurrent runtime delivery keeps match correlation isolated") {
            val capture = LogCapture(MatchRuntimeContinuation::class.java)
            val firstBoard = startWithBoard { _, _, _ -> }
            val secondLifecycle = IsolatedBoardLifecycle()
            val secondBoard = secondLifecycle.startWithBoard { _, _, _ -> }
            val first = runtimeSession("match-one", firstBoard.bridge)
            val second = runtimeSession("match-two", secondBoard.bridge)
            val firstObserver = MatchRuntimeDeliveryObserver(first.session, SeatId(1), MatchRuntimeDeliveryGeneration())
            val secondObserver = MatchRuntimeDeliveryObserver(second.session, SeatId(1), MatchRuntimeDeliveryGeneration())

            try {
                firstObserver.start()
                secondObserver.start()

                assertSoftly {
                    first.sink.delivered.await(2, TimeUnit.SECONDS) shouldBe true
                    second.sink.delivered.await(2, TimeUnit.SECONDS) shouldBe true
                }
                awaitEvents(capture, expected = 2)

                val deliveredEvents = capture.events.filter { it.message == "Match horizon delivered" }
                deliveredEvents.forEach { event ->
                    event.keyValuePairs.map { it.key }.toSet() shouldBe setOf("event", "match_id", "seat")
                }
                val delivered = deliveredEvents.map { it.value("match_id") }
                delivered shouldContainExactlyInAnyOrder listOf("match-one", "match-two")
            } finally {
                firstObserver.stop()
                secondObserver.stop()
                awaitStopped(firstObserver)
                awaitStopped(secondObserver)
                secondLifecycle.tearDown()
                capture.close()
            }
        }

        test("empty runtime drain does not emit a delivered event") {
            val capture = LogCapture(MatchRuntimeContinuation::class.java)
            val board = startWithBoard { _, _, _ -> }

            try {
                runtimeSession("empty-match", board.bridge, enqueue = false).session.deliverRuntimeHorizon()
                capture.events.filter { it.message == "Match horizon delivered" }.shouldBeEmpty()
            } finally {
                capture.close()
            }
        }

        test("prompt send failure does not publish a prompt event") {
            val capture = LogCapture(MatchSession::class.java)
            val board = startWithBoard { _, _, _ -> }
            val cause = IllegalStateException("synthetic prompt send failure")
            val session =
                MatchSession(
                    connection =
                        ConnectionState(
                            seatId = SeatId(1),
                            matchId = "failed-prompt",
                            sink =
                                object : MessageSink {
                                    override fun send(messages: List<GREToClientMessage>) = throw cause

                                    override fun sendRaw(msg: wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage) = Unit
                                },
                            registry = MatchRegistry(),
                        ),
                    gameBridge = board.bridge,
                    paceDelayMs = 0,
                )

            try {
                shouldThrow<IllegalStateException> {
                    session.sendLifecycleGRE(
                        listOf(
                            GREToClientMessage
                                .newBuilder()
                                .setType(GREMessageType.ActionsAvailableReq_695e)
                                .setGameStateId(17)
                                .build(),
                        ),
                    )
                }
                capture.events.filter { it.message == "Match prompt published" }.shouldBeEmpty()
            } finally {
                session.close()
                board.bridge.shutdown()
                capture.close()
            }
        }

        test("runtime delivery failure emits one owned structured stack") {
            val capture = LogCapture(MatchRuntimeDeliveryObserver::class.java)
            val board = startWithBoard { _, _, _ -> }
            val cause = IllegalStateException("synthetic runtime delivery failure")
            val session = failingRuntimeSession("failed-match", board.bridge, cause)
            val observer = MatchRuntimeDeliveryObserver(session, SeatId(1), MatchRuntimeDeliveryGeneration())

            try {
                observer.start()
                awaitFailure(capture)

                val events = capture.events.filter { it.message == "Runtime horizon delivery failed" }
                assertSoftly {
                    events.size shouldBe 1
                    val event = events.single()
                    event.level shouldBe Level.ERROR
                    event.value("event") shouldBe "match.runtime_delivery_failed"
                    event.value("match_id") shouldBe "failed-match"
                    event.value("seat") shouldBe "1"
                    event.throwableProxy.shouldNotBeNull().className shouldBe PlaybackTerminalFailure::class.java.name
                }
            } finally {
                observer.stop()
                awaitStopped(observer)
                board.bridge.shutdown()
                capture.close()
            }
        }

        test("result reporting failure carries match correlation") {
            val capture = LogCapture(MatchSession::class.java)
            val board = startWithBoard { _, _, _ -> }
            val cause = IllegalStateException("synthetic result reporting failure")
            val bridge = board.bridge
            val registry = MatchRegistry()
            bridge.cutCoordinator.registerViewers(
                listOf(ProjectionViewer(SeatId(1), ProjectionViewerRole.Player)),
            )
            val session =
                MatchSession(
                    connection =
                        ConnectionState(
                            seatId = SeatId(1),
                            matchId = "failed-result",
                            sink = AwaitingSink(),
                            registry = registry,
                            coordinator = FailingResultCoordinator(cause),
                        ),
                    gameBridge = bridge,
                    paceDelayMs = 0,
                )

            try {
                bridge.cutCoordinator.publishConcession(SeatId(1))
                session.sendGameOver()

                val events = capture.events.filter { it.message == "Match result reporting failed" }
                assertSoftly {
                    events.size shouldBe 1
                    val event = events.single()
                    event.level shouldBe Level.ERROR
                    event.value("event") shouldBe "match.result_reporting_failed"
                    event.value("match_id") shouldBe "failed-result"
                    event.value("seat") shouldBe "1"
                    event.throwableProxy.shouldNotBeNull().className shouldBe IllegalStateException::class.java.name
                }
            } finally {
                session.close()
                bridge.shutdown()
                capture.close()
            }
        }
    })

private data class RuntimeSession(
    val session: MatchSession,
    val sink: AwaitingSink,
)

private fun runtimeSession(
    matchId: String,
    bridge: GameBridge,
    enqueue: Boolean = true,
): RuntimeSession {
    val sink = AwaitingSink()
    val session =
        MatchSession(
            connection =
                ConnectionState(
                    seatId = SeatId(1),
                    matchId = matchId,
                    sink = sink,
                    registry = MatchRegistry(),
                ),
            gameBridge = bridge,
            paceDelayMs = 0,
        )
    bridge.cutCoordinator.registerViewer(SeatId(1))
    if (enqueue) {
        bridge.cutCoordinator.enqueueCommittedBatchForTest(
            SeatId(1),
            listOf(GREToClientMessage.newBuilder().setGameStateId(17).build()),
        )
    }
    return RuntimeSession(session, sink)
}

private fun failingRuntimeSession(
    matchId: String,
    bridge: GameBridge,
    cause: Throwable,
): MatchSession {
    val session =
        MatchSession(
            connection =
                ConnectionState(
                    seatId = SeatId(1),
                    matchId = matchId,
                    sink =
                        object : MessageSink {
                            override fun send(messages: List<GREToClientMessage>) = throw cause

                            override fun sendRaw(msg: wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage) = Unit
                        },
                    registry = MatchRegistry(),
                ),
            gameBridge = bridge,
            paceDelayMs = 0,
        )
    bridge.cutCoordinator.registerViewer(SeatId(1))
    bridge.cutCoordinator.enqueueCommittedBatchForTest(
        SeatId(1),
        listOf(GREToClientMessage.newBuilder().setGameStateId(17).build()),
    )
    return session
}

private class FailingResultCoordinator(
    private val cause: Throwable,
) : MatchCoordinator by MatchCoordinator.NOOP {
    override fun reportMatchResult(
        matchId: String,
        won: Boolean,
    ) = throw cause
}

private class AwaitingSink : MessageSink {
    val delivered = CountDownLatch(1)

    @Synchronized
    override fun send(messages: List<GREToClientMessage>) {
        delivered.countDown()
    }

    override fun sendRaw(msg: wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage) = Unit
}

private class LogCapture(
    type: Class<*>,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(type) as Logger
    private val previousLevel = logger.level
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    val events: List<ILoggingEvent>
        get() = appender.list.toList()

    init {
        logger.level = Level.DEBUG
        logger.addAppender(appender)
    }

    override fun close() {
        logger.detachAppender(appender)
        logger.level = previousLevel
        appender.stop()
    }
}

private fun ILoggingEvent.value(key: String): String = keyValuePairs.first { it.key == key }.value.toString()

private fun awaitStopped(observer: MatchRuntimeDeliveryObserver) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (observer.isAlive && System.nanoTime() < deadline) Thread.onSpinWait()
    observer.isAlive shouldBe false
}

private fun awaitEvents(
    capture: LogCapture,
    expected: Int,
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (capture.events.count { it.message == "Match horizon delivered" } < expected && System.nanoTime() < deadline) {
        Thread.onSpinWait()
    }
}

private fun awaitFailure(capture: LogCapture) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (capture.events.none { it.message == "Runtime horizon delivery failed" } && System.nanoTime() < deadline) {
        Thread.onSpinWait()
    }
    capture.events.any { it.message == "Runtime horizon delivery failed" } shouldBe true
}
