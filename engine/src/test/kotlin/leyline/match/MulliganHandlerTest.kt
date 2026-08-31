package leyline.match

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.infra.ListMessageSink
import leyline.infra.MessageSink
import leyline.testkit.BoardTest
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MulliganOption
import wotc.mtgo.gre.external.messaging.Messages.MulliganResp

class MulliganHandlerTest :
    BoardTest({

        data class SessionFixture(
            val session: MatchSession,
            val sink: ListMessageSink,
        )

        fun sessionFor(
            seatId: SeatId,
            registry: MatchRegistry,
            bridge: leyline.game.state.GameBridge,
        ): SessionFixture {
            val sink = ListMessageSink()
            return SessionFixture(
                MatchSession(
                    connection =
                        ConnectionState(
                            seatId = seatId,
                            matchId = "mulligan-handler-test",
                            sink = sink,
                            registry = registry,
                        ),
                    gameBridge = bridge,
                    paceDelayMs = 0,
                ),
                sink,
            )
        }

        fun handler(
            seatId: SeatId,
            session: MatchSession,
            registry: MatchRegistry = session.registry,
        ): MulliganHandler =
            MulliganHandler(
                EngineSettings(),
                registry,
                sessionProvider = { session },
                matchIdProvider = { session.matchId },
                seatIdProvider = { seatId },
            )

        fun mulliganResp(decision: MulliganOption): ClientToGREMessage =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.MulliganResp_097b)
                .setMulliganResp(MulliganResp.newBuilder().setDecision(decision))
                .build()

        test("MulliganResp from familiar seat is ignored") {
            val (bridge, _, _) = startWithBoard { _, _, _ -> }
            val registry = MatchRegistry()
            val (session, sink) = sessionFor(SeatId(2), registry, bridge)
            val mulligan = handler(SeatId(2), session, registry)

            mulligan.onMulliganResp(mulliganResp(MulliganOption.AcceptHand))

            mulligan.mulliganCount shouldBe 0
            sink.messages.shouldBeEmpty()
        }

        test("GroupResp from familiar seat is ignored") {
            val (bridge, _, _) = startWithBoard { _, _, _ -> }
            val registry = MatchRegistry()
            val (session, sink) = sessionFor(SeatId(2), registry, bridge)
            val mulligan = handler(SeatId(2), session, registry)

            mulligan.onGroupResp(ClientToGREMessage.newBuilder().setType(ClientMessageType.GroupResp_097b).build())

            sink.messages.shouldBeEmpty()
        }

        test("sendMulliganReq emits thin GSM, PromptReq, and MulliganReq") {
            val (bridge, _, _) =
                startWithBoard { _, human, ai ->
                    repeat(7) { addCard("Forest", human, forge.game.zone.ZoneType.Hand) }
                    repeat(7) { addCard("Forest", ai, forge.game.zone.ZoneType.Hand) }
                }
            val registry = MatchRegistry()
            val (session, sink) = sessionFor(SeatId(1), registry, bridge)
            val mulligan = handler(SeatId(1), session, registry)

            mulligan.sendMulliganReq(reportedMulliganCount = 1, numCards = 6)

            val messages = sink.messages
            assertSoftly {
                messages shouldHaveSize 3
                messages.map { it.type } shouldBe
                    listOf(
                        GREMessageType.GameStateMessage_695e,
                        GREMessageType.PromptReq,
                        GREMessageType.MulliganReq_aa0d,
                    )
                messages.map { it.gameStateId }.toSet() shouldBe setOf(21)
                messages.last().mulliganReq.mulliganCount shouldBe 1
                messages
                    .last()
                    .prompt
                    .parametersList
                    .map { it.numberValue } shouldContain 6
            }
        }

        test("sendDealHandPublic emits a DealHand GSM") {
            val (bridge, _, _) =
                startWithBoard { _, human, _ ->
                    repeat(7) { addCard("Forest", human, forge.game.zone.ZoneType.Hand) }
                }
            val registry = MatchRegistry()
            val (session, sink) = sessionFor(SeatId(1), registry, bridge)
            val mulligan = handler(SeatId(1), session, registry)

            mulligan.sendDealHandPublic()

            val messages = sink.messages
            assertSoftly {
                messages shouldHaveSize 1
                messages.single().type shouldBe GREMessageType.GameStateMessage_695e
                messages
                    .single()
                    .gameStateMessage
                    .playersList
                    .map { it.pendingMessageType } shouldContain ClientMessageType.MulliganResp_097b
            }
        }

        test("failed lifecycle delivery does not emit a template event") {
            val (bridge, _, _) =
                startWithBoard { _, human, ai ->
                    repeat(7) { addCard("Forest", human, forge.game.zone.ZoneType.Hand) }
                    repeat(7) { addCard("Forest", ai, forge.game.zone.ZoneType.Hand) }
                }
            val registry = MatchRegistry()
            val sink =
                object : MessageSink {
                    override fun send(messages: List<GREToClientMessage>): Unit = error("delivery failed")

                    override fun sendRaw(msg: MatchServiceToClientMessage) = Unit
                }
            val session =
                MatchSession(
                    connection =
                        ConnectionState(
                            seatId = SeatId(1),
                            matchId = "mulligan-handler-test",
                            sink = sink,
                            registry = registry,
                        ),
                    gameBridge = bridge,
                    paceDelayMs = 0,
                )
            val mulligan = handler(SeatId(1), session, registry)
            val context = LoggerFactory.getILoggerFactory() as LoggerContext
            val logger = context.getLogger(Tap::class.java) as Logger
            val appender =
                ListAppender<ILoggingEvent>().apply {
                    this.context = context
                    start()
                }
            val priorLevel = logger.level
            val priorAdditive = logger.isAdditive
            logger.level = Level.DEBUG
            logger.isAdditive = false
            logger.addAppender(appender)

            try {
                shouldThrow<IllegalStateException> {
                    mulligan.sendMulliganReq(reportedMulliganCount = 1, numCards = 6)
                }
                appender.list.none { event ->
                    event.keyValuePairs.any { it.key == "event" && it.value == "client.template_sent" }
                } shouldBe true
            } finally {
                logger.detachAppender(appender)
                logger.level = priorLevel
                logger.isAdditive = priorAdditive
                appender.stop()
            }
        }
    })
