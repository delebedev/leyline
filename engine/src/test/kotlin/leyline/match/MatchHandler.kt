package leyline.match

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import leyline.config.MatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.service.MatchCoordinator
import leyline.game.data.CardRepository
import leyline.infra.MatchOutput
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage

/** Test-only Netty edge adapter for legacy match-flow fixtures. */
class MatchHandler(
    private val registry: MatchRegistry,
    private val matchConfig: MatchConfig = MatchConfig(),
    private val coordinator: MatchCoordinator? = null,
    private val cardRepository: CardRepository,
    private val debugSink: MatchDebugSink? = null,
    private val recorderFactory: (() -> MatchRecorder)? = null,
    private val puzzlePath: () -> String? = { null },
    private val runtimeMatchConfigs: RuntimeMatchConfigRegistry? = null,
    private val aiDeckNameOverride: () -> String? = { null },
) : SimpleChannelInboundHandler<ClientToMatchServiceMessage>() {
    lateinit var connection: MatchConnection
        private set

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        connection =
            MatchConnection(
                registry = registry,
                output =
                    object : MatchOutput {
                        override fun send(message: MatchServiceToClientMessage) {
                            ctx.writeAndFlush(message)
                        }

                        override fun close() {
                            ctx.close()
                        }
                    },
                matchConfig = matchConfig,
                coordinator = coordinator,
                cardRepository = cardRepository,
                debugSink = debugSink,
                recorderFactory = recorderFactory,
                puzzlePath = puzzlePath,
                runtimeMatchConfigs = runtimeMatchConfigs,
                aiDeckNameOverride = aiDeckNameOverride,
            )
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        connection.opened()
    }

    override fun channelRead0(
        ctx: ChannelHandlerContext,
        msg: ClientToMatchServiceMessage,
    ) {
        runCatching { connection.receive(msg) }.onFailure(connection::failed)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        connection.disconnected()
    }

    override fun exceptionCaught(
        ctx: ChannelHandlerContext,
        cause: Throwable,
    ) {
        connection.failed(cause)
    }

    var session: SessionOps?
        get() = connection.session
        set(value) {
            connection.session = value
        }
}
