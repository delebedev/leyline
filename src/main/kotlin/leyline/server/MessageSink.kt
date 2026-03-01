package leyline.server

import leyline.protocol.ProtoDump
import io.netty.channel.ChannelHandlerContext
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Abstraction over "send messages to the client". Production: wraps Netty ctx.
 * Tests: captures messages into a list.
 */
interface MessageSink {
    /** Send GRE messages bundled in a GreToClientEvent. */
    fun send(messages: List<GREToClientMessage>)

    /** Send a raw MatchServiceToClientMessage (auth, room state, etc.). */
    fun sendRaw(msg: MatchServiceToClientMessage)
}

/** Test sink that captures all messages for assertion. */
class ListMessageSink : MessageSink {
    val messages = mutableListOf<GREToClientMessage>()
    val rawMessages = mutableListOf<MatchServiceToClientMessage>()

    override fun send(messages: List<GREToClientMessage>) {
        this.messages.addAll(messages)
    }

    override fun sendRaw(msg: MatchServiceToClientMessage) {
        rawMessages.add(msg)
    }

    fun clear() {
        messages.clear()
        rawMessages.clear()
    }
}

/** Production sink: wraps Netty [ChannelHandlerContext.writeAndFlush]. */
class NettyMessageSink(
    private val ctx: ChannelHandlerContext,
    /** When false, skips ProtoDump — used for mirror/familiar sinks to avoid duplicate .bin files. */
    private val dumpEnabled: Boolean = true,
) : MessageSink {
    override fun send(messages: List<GREToClientMessage>) {
        val event = GreToClientEvent.newBuilder()
        messages.forEach { event.addGreToClientMessages(it) }
        val msg = MatchServiceToClientMessage.newBuilder()
            .setGreToClientEvent(event.build())
            .build()
        if (dumpEnabled) ProtoDump.dump(msg)
        ctx.writeAndFlush(msg)
    }

    override fun sendRaw(msg: MatchServiceToClientMessage) {
        ctx.writeAndFlush(msg)
    }
}
