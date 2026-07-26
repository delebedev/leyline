package leyline.match

import leyline.bridge.types.SeatId
import leyline.game.bundle.MessageCounter
import leyline.infra.MessageSink
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import java.util.ArrayDeque

/**
 * Owner-confined ordering authority for match-progress output.
 *
 * The outbox stores protocol values and opaque destination generations only.
 * Protocol heads retain transport handles and synchronously flush committed
 * entries. A head acknowledges an entry only after its sink accepts it.
 */
internal class MatchOutbox {
    sealed interface Audience {
        data class Player(
            val seatId: SeatId,
        ) : Audience

        data class Familiar(
            val seatId: SeatId,
        ) : Audience

        data class Spectator(
            val seatId: SeatId,
        ) : Audience
    }

    sealed interface Payload {
        data class Gre(
            val messages: List<GREToClientMessage>,
        ) : Payload

        data class Raw(
            val message: MatchServiceToClientMessage,
        ) : Payload
    }

    data class HeadToken(
        internal val generation: Long,
    )

    data class Delivery(
        val sequence: Long,
        val audience: Audience,
        val payload: Payload,
    )

    private data class HeadState(
        val audience: Audience,
        val pending: ArrayDeque<Delivery> = ArrayDeque(),
    )

    private var nextGeneration = 1L
    private var nextSequence = 1L
    private val currentHeads = mutableMapOf<Audience, HeadToken>()
    private val heads = mutableMapOf<HeadToken, HeadState>()

    fun bind(audience: Audience): HeadToken {
        currentHeads.remove(audience)?.let(heads::remove)
        val token = HeadToken(nextGeneration++)
        currentHeads[audience] = token
        heads[token] = HeadState(audience)
        return token
    }

    fun release(token: HeadToken) {
        val state = heads.remove(token) ?: return
        currentHeads.remove(state.audience, token)
    }

    fun append(entries: List<Pair<HeadToken, Payload>>): Long {
        require(entries.isNotEmpty()) { "Outbox append requires at least one destination" }
        require(entries.map { it.first }.distinct().size == entries.size) {
            "Outbox append cannot target one head twice"
        }
        val destinations =
            entries.map { (token, payload) ->
                val state = checkNotNull(heads[token]) { "Outbox head is stale" }
                check(currentHeads[state.audience] == token) { "Outbox head is displaced" }
                Triple(token, state, payload)
            }
        val sequence = nextSequence++
        destinations.forEach { (_, state, payload) ->
            state.pending.addLast(Delivery(sequence, state.audience, payload))
        }
        return sequence
    }

    fun peek(token: HeadToken): Delivery? = heads[token]?.pending?.firstOrNull()

    fun acknowledge(
        token: HeadToken,
        sequence: Long,
    ) {
        val state = checkNotNull(heads[token]) { "Outbox head is stale" }
        val current = checkNotNull(state.pending.firstOrNull()) { "Outbox head has no pending delivery" }
        check(current.sequence == sequence) {
            "Outbox acknowledgement $sequence skipped pending ${current.sequence}"
        }
        state.pending.removeFirst()
    }
}

/**
 * Transport-bearing head for one outbox audience generation.
 *
 * Delivery exceptions leave the current entry pending. Later entries cannot
 * become visible until the failed prefix is accepted and acknowledged.
 */
internal class MatchProtocolHead(
    private val owner: MatchOwner,
    audience: MatchOutbox.Audience,
    private val sink: MessageSink,
) {
    internal val token = owner.reduce { owner.bindOutboxHead(audience) }
    private var inFlightSequence: Long? = null
    private val drainedCallbacks = ArrayDeque<() -> Unit>()

    fun flush() {
        owner.assertOwnerThread()
        if (inFlightSequence != null) return
        val delivery = owner.peekOutbox(token) ?: return
        inFlightSequence = delivery.sequence
        try {
            when (val payload = delivery.payload) {
                is MatchOutbox.Payload.Gre ->
                    sink.send(payload.messages) { error -> complete(delivery.sequence, error) }
                is MatchOutbox.Payload.Raw ->
                    sink.sendRaw(payload.message) { error -> complete(delivery.sequence, error) }
            }
        } catch (error: Throwable) {
            inFlightSequence = null
            throw error
        }
    }

    private fun complete(
        sequence: Long,
        error: Throwable?,
    ) {
        val completion = {
            if (inFlightSequence == sequence) {
                inFlightSequence = null
                if (error == null && owner.peekOutbox(token)?.sequence == sequence) {
                    owner.acknowledgeOutbox(token, sequence)
                    flush()
                    runDrainedCallbacks()
                }
            }
        }
        if (owner.isOwnerThread()) {
            completion()
        } else {
            owner.enqueue(completion)
        }
    }

    fun afterDrained(action: () -> Unit) {
        owner.assertOwnerThread()
        if (inFlightSequence == null && owner.peekOutbox(token) == null) {
            action()
        } else {
            drainedCallbacks.addLast(action)
        }
    }

    private fun runDrainedCallbacks() {
        if (inFlightSequence != null || owner.peekOutbox(token) != null) return
        while (drainedCallbacks.isNotEmpty()) drainedCallbacks.removeFirst()()
    }

    fun close() {
        if (owner.isClosed()) return
        owner.reduce { owner.releaseOutboxHead(token) }
    }
}

internal class MatchSessionOutbox(
    private val owner: MatchOwner,
    seatId: SeatId,
    sink: MessageSink,
    private val counter: MessageCounter,
    private val recorder: MatchRecorder?,
    private val familiarProvider: () -> FamiliarSession?,
) {
    private val head = MatchProtocolHead(owner, MatchOutbox.Audience.Player(seatId), sink)
    private var familiarHead: MatchProtocolHead? = null

    fun sendRaw(message: MatchServiceToClientMessage) {
        owner.observeOutbound(message)
        owner.appendOutbox(listOf(head.token to MatchOutbox.Payload.Raw(message)))
        head.flush()
    }

    fun sendGre(
        messages: List<GREToClientMessage>,
        mirror: Boolean,
    ) {
        owner.observeOutbound(messages)
        messages
            .filter(GREToClientMessage::hasGameStateMessage)
            .forEach { counter.markGameStateGsId(it.gameStateMessage.gameStateId) }
        recorder?.recordOutbound(messages)

        val familiar = if (mirror) familiarProvider() else null
        val mirrored = familiar?.let { FamiliarMessageAdapter.adapt(messages) }.orEmpty()
        val entries =
            buildList {
                add(head.token to MatchOutbox.Payload.Gre(messages))
                if (familiar != null && mirrored.isNotEmpty()) {
                    add(familiar.protocolHead.token to MatchOutbox.Payload.Gre(mirrored))
                    familiarHead = familiar.protocolHead
                }
            }
        owner.appendOutbox(entries)
        head.flush()
        if (mirrored.isNotEmpty()) familiar?.protocolHead?.flush()
    }

    fun afterDrained(action: () -> Unit) {
        val mirror = familiarHead
        if (mirror == null) {
            head.afterDrained(action)
            return
        }
        var remaining = 2
        val complete = {
            remaining -= 1
            if (remaining == 0) action()
        }
        head.afterDrained(complete)
        mirror.afterDrained(complete)
    }

    fun close() = head.close()
}
