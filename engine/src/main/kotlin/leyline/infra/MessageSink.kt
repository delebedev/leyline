package leyline.infra

import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Abstraction over "send messages to the client". Production: wraps Netty ctx.
 * Tests: collects messages into a list.
 */
interface MessageSink {
    /** Send GRE messages bundled in a GreToClientEvent. */
    fun send(messages: List<GREToClientMessage>)

    fun send(
        messages: List<GREToClientMessage>,
        completion: (Throwable?) -> Unit,
    ) {
        try {
            send(messages)
            completion(null)
        } catch (error: Throwable) {
            completion(error)
            throw error
        }
    }

    /** Send a raw MatchServiceToClientMessage (auth, room state, etc.). */
    fun sendRaw(msg: MatchServiceToClientMessage)

    fun sendRaw(
        msg: MatchServiceToClientMessage,
        completion: (Throwable?) -> Unit,
    ) {
        try {
            sendRaw(msg)
            completion(null)
        } catch (error: Throwable) {
            completion(error)
            throw error
        }
    }
}

sealed interface RecordedDelivery {
    data class Gre(
        val messages: List<GREToClientMessage>,
    ) : RecordedDelivery

    data class Raw(
        val message: MatchServiceToClientMessage,
    ) : RecordedDelivery
}

/** Test sink that collects all messages for assertion. */
class ListMessageSink : MessageSink {
    val messages = mutableListOf<GREToClientMessage>()
    val rawMessages = mutableListOf<MatchServiceToClientMessage>()
    val deliveries = mutableListOf<RecordedDelivery>()

    override fun send(messages: List<GREToClientMessage>) {
        this.messages.addAll(messages)
        deliveries += RecordedDelivery.Gre(messages)
    }

    override fun sendRaw(msg: MatchServiceToClientMessage) {
        rawMessages.add(msg)
        deliveries += RecordedDelivery.Raw(msg)
    }

    fun clear() {
        messages.clear()
        rawMessages.clear()
        deliveries.clear()
    }
}

/** Transport-neutral raw match output. */
interface MatchOutput {
    fun send(message: MatchServiceToClientMessage)

    fun send(
        message: MatchServiceToClientMessage,
        completion: (Throwable?) -> Unit,
    ) {
        try {
            send(message)
            completion(null)
        } catch (error: Throwable) {
            completion(error)
            throw error
        }
    }

    fun close()
}

/** Wraps a raw [MatchOutput] in the GRE-focused [MessageSink] used by sessions. */
class MatchOutputMessageSink(
    private val output: MatchOutput,
    /** When false, skips ProtoDump — used for mirror/familiar sinks to avoid duplicate .bin files. */
    private val dumpEnabled: Boolean = true,
) : MessageSink {
    override fun send(messages: List<GREToClientMessage>) {
        send(messages) {}
    }

    override fun send(
        messages: List<GREToClientMessage>,
        completion: (Throwable?) -> Unit,
    ) {
        val event = GreToClientEvent.newBuilder()
        messages.forEach { event.addGreToClientMessages(it) }
        val msg =
            MatchServiceToClientMessage
                .newBuilder()
                .setGreToClientEvent(event.build())
                .build()
        if (dumpEnabled) leyline.protocol.ProtoDump.dump(msg)
        output.send(msg, completion)
    }

    override fun sendRaw(msg: MatchServiceToClientMessage) {
        sendRaw(msg) {}
    }

    override fun sendRaw(
        msg: MatchServiceToClientMessage,
        completion: (Throwable?) -> Unit,
    ) {
        output.send(msg, completion)
    }
}
