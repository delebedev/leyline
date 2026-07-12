package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import leyline.bridge.types.SeatId
import leyline.config.MatchConfig
import leyline.infra.ListMessageSink
import leyline.infra.MatchOutput
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MulliganOption
import wotc.mtgo.gre.external.messaging.Messages.MulliganResp

class MulliganHandlerTest :
    BoardTest({

        fun channelCtx(): Pair<EmbeddedChannel, ChannelHandlerContext> {
            val probe = object : ChannelInboundHandlerAdapter() {}
            val channel = EmbeddedChannel(probe)
            return channel to (channel.pipeline().context(probe) as ChannelHandlerContext)
        }

        fun outbound(channel: EmbeddedChannel): List<MatchServiceToClientMessage> =
            generateSequence { channel.readOutbound<MatchServiceToClientMessage>() }.toList()

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
            ctx: ChannelHandlerContext? = null,
        ): MulliganHandler =
            MulliganHandler(
                MatchConfig(),
                registry,
                sessionProvider = { session },
                outputProvider = {
                    object : MatchOutput {
                        override fun send(message: MatchServiceToClientMessage) {
                            ctx?.writeAndFlush(message)
                        }

                        override fun close() {
                            ctx?.close()
                        }
                    }
                },
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
            val (session, _) = sessionFor(SeatId(1), registry, bridge)
            val (channel, ctx) = channelCtx()
            val mulligan = handler(SeatId(1), session, registry, ctx)

            mulligan.sendMulliganReq(reportedMulliganCount = 1, numCards = 6)

            val messages = outbound(channel).flatMap { it.greToClientEvent.greToClientMessagesList }
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
                    .map { it.numberValue.toInt() } shouldContain 6
            }
        }

        test("sendDealHandPublic emits a DealHand GSM") {
            val (bridge, _, _) =
                startWithBoard { _, human, _ ->
                    repeat(7) { addCard("Forest", human, forge.game.zone.ZoneType.Hand) }
                }
            val registry = MatchRegistry()
            val (session, _) = sessionFor(SeatId(1), registry, bridge)
            val (channel, ctx) = channelCtx()
            val mulligan = handler(SeatId(1), session, registry, ctx)

            mulligan.sendDealHandPublic()

            val messages = outbound(channel).flatMap { it.greToClientEvent.greToClientMessagesList }
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
    })
