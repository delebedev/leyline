package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.embedded.EmbeddedChannel
import leyline.IntegrationTag
import leyline.game.GameBridge
import leyline.infra.ListMessageSink
import leyline.match.Match
import leyline.match.MatchHandler
import leyline.match.MatchRegistry
import leyline.match.MatchSession
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MulliganOption
import wotc.mtgo.gre.external.messaging.Messages.MulliganResp

class MulliganHandlerIntegrationTest :
    FunSpec({

        tags(IntegrationTag)

        val matchId = "mull-test"
        var bridge: GameBridge? = null

        beforeSpec {
            leyline.bridge.GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        afterEach {
            bridge?.shutdown()
            bridge = null
        }

        fun MatchHandler.setPrivate(name: String, value: Any?) {
            val field = MatchHandler::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(this, value)
        }

        fun bindHandler(
            handler: MatchHandler,
            seatId: Int,
            session: MatchSession,
            registry: MatchRegistry,
        ): EmbeddedChannel {
            val channel = EmbeddedChannel(handler)
            val ctx = channel.pipeline().context(handler) as ChannelHandlerContext
            handler.session = session
            handler.setPrivate("matchId", matchId)
            handler.setPrivate("seatId", seatId)
            handler.setPrivate("nettyCtx", ctx)
            registry.registerHandler(matchId, seatId, handler)
            return channel
        }

        fun outbound(channel: EmbeddedChannel): List<MatchServiceToClientMessage> =
            generateSequence { channel.readOutbound<MatchServiceToClientMessage>() }.toList()

        fun greMessages(msg: MatchServiceToClientMessage): List<GREToClientMessage> =
            msg.greToClientEvent.greToClientMessagesList

        fun mulliganResp(option: MulliganOption): ClientToGREMessage =
            ClientToGREMessage.newBuilder()
                .setType(ClientMessageType.MulliganResp_097b)
                .setMulliganResp(MulliganResp.newBuilder().setDecision(option))
                .build()

        fun setupTwoSeatMulligan(): Quint<MatchRegistry, MatchSession, MatchSession, ListMessageSink, Pair<EmbeddedChannel, EmbeddedChannel>> {
            val registry = MatchRegistry()
            val localBridge = GameBridge(bridgeTimeoutMs = 5_000L, cards = TestCardRegistry.repo)
            bridge = localBridge
            localBridge.priorityWaitMs = 2_000L
            localBridge.startTwoPlayer(seed = 42L)
            registry.getOrCreateMatch(matchId) { Match(matchId, localBridge) }

            val sink1 = ListMessageSink()
            val seat1 = MatchSession(
                seatId = 1,
                matchId = matchId,
                sink = sink1,
                registry = registry,
                paceDelayMs = 0,
            )
            val seat2 = MatchSession(
                seatId = 2,
                matchId = matchId,
                sink = ListMessageSink(),
                registry = registry,
                paceDelayMs = 0,
            )
            seat1.connectBridge(localBridge)
            seat2.connectBridge(localBridge)
            registry.registerSession(matchId, 1, seat1)
            registry.registerSession(matchId, 2, seat2)

            val handler1 = MatchHandler(registry = registry)
            val handler2 = MatchHandler(registry = registry)
            val channel1 = bindHandler(handler1, 1, seat1, registry)
            val channel2 = bindHandler(handler2, 2, seat2, registry)
            return Quint(registry, seat1, seat2, sink1, channel1 to channel2)
        }

        test("choose starting player fans mulligan prompts to both seats") {
            val (_, _, _, _, channels) = setupTwoSeatMulligan()
            val (seat1Channel, seat2Channel) = channels

            val seat2Handler = seat2Channel.pipeline().get(MatchHandler::class.java)
            seat2Handler.mulliganHandler.onChooseStartingPlayer(seat2Handler)

            val seat1Gre = outbound(seat1Channel).flatMap(::greMessages)
            val seat2Gre = outbound(seat2Channel).flatMap(::greMessages)

            seat2Gre.map { it.type } shouldContainExactly listOf(
                GREMessageType.GameStateMessage_695e,
                GREMessageType.MulliganReq_aa0d,
            )
            seat1Gre.map { it.type } shouldContainExactly listOf(
                GREMessageType.GameStateMessage_695e,
                GREMessageType.GameStateMessage_695e,
                GREMessageType.PromptReq,
                GREMessageType.MulliganReq_aa0d,
            )
        }

        test("mulligan resp redraws and lowers mulligan prompt card count") {
            val (_, _, _, _, channels) = setupTwoSeatMulligan()
            val (seat1Channel, seat2Channel) = channels

            val seat2Handler = seat2Channel.pipeline().get(MatchHandler::class.java)
            seat2Handler.mulliganHandler.onChooseStartingPlayer(seat2Handler)
            outbound(seat1Channel)
            outbound(seat2Channel)

            val seat1Handler = seat1Channel.pipeline().get(MatchHandler::class.java)
            seat1Handler.mulliganHandler.onMulliganResp(mulliganResp(MulliganOption.Mulligan))

            val seat1Gre = outbound(seat1Channel).flatMap(::greMessages)
            val mullReq = seat1Gre.last { it.type == GREMessageType.MulliganReq_aa0d }

            seat1Handler.mulliganHandler.mulliganCount shouldBe 1
            seat1Handler.mulliganHandler.seat1Hand.size shouldBe 6
            mullReq.prompt.parametersList.first { it.parameterName == "NumberOfCards" }.numberValue shouldBe 6
            mullReq.mulliganReq.mulliganCount shouldBe 0
            seat1Gre.map { it.type } shouldContain GREMessageType.GameStateMessage_695e
        }
    })

private data class Quint<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
)
