package leyline.infra

import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Abstraction over "send messages to the client". Production: wraps Netty ctx.
 * Tests: collects messages into a list.
 */
interface MessageSink {
    /** Send GRE messages bundled in a GreToClientEvent. */
    fun send(messages: List<GREToClientMessage>)

    /** Send a raw MatchServiceToClientMessage (auth, room state, etc.). */
    fun sendRaw(msg: MatchServiceToClientMessage)
}

/** Test sink that collects all messages for assertion. */
class ListMessageSink : MessageSink {
    val messages = mutableListOf<GREToClientMessage>()
    val rawMessages = mutableListOf<MatchServiceToClientMessage>()

    @Synchronized
    override fun send(messages: List<GREToClientMessage>) {
        this.messages.addAll(messages)
    }

    @Synchronized
    override fun sendRaw(msg: MatchServiceToClientMessage) {
        rawMessages.add(msg)
    }

    @Synchronized
    fun clear() {
        messages.clear()
        rawMessages.clear()
    }
}

/** Transport-neutral raw match output. */
interface MatchOutput {
    fun send(message: MatchServiceToClientMessage)

    fun close()
}

/** Wraps a raw [MatchOutput] in the GRE-focused [MessageSink] used by sessions. */
class MatchOutputMessageSink(
    private val output: MatchOutput,
) : MessageSink {
    override fun send(messages: List<GREToClientMessage>) {
        val event = GreToClientEvent.newBuilder()
        messages.forEach { event.addGreToClientMessages(it) }
        val msg =
            MatchServiceToClientMessage
                .newBuilder()
                .setGreToClientEvent(event.build())
                .build()
        output.send(msg)
    }

    override fun sendRaw(msg: MatchServiceToClientMessage) {
        output.send(msg)
    }
}
