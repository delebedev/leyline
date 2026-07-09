package leyline.native.matchdoor

import io.netty.channel.ChannelHandlerContext
import leyline.infra.MatchOutput
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage

internal class NettyMatchOutput(
    private val ctx: ChannelHandlerContext,
) : MatchOutput {
    override fun send(message: MatchServiceToClientMessage) {
        ctx.writeAndFlush(message)
    }

    override fun close() {
        ctx.close()
    }
}
