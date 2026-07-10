package leyline.native.matchdoor

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import leyline.infra.MatchOutput
import leyline.match.MatchConnection
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage

/** Netty lifecycle adapter; channel event-loop serialization stays at this edge. */
internal class NativeMatchConnectionHandler(
    private val connectionFactory: (MatchOutput) -> MatchConnection,
) : SimpleChannelInboundHandler<ClientToMatchServiceMessage>() {
    private lateinit var connection: MatchConnection

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        connection = connectionFactory(NettyMatchOutput(ctx))
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
}
