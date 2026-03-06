package leyline.debug

import leyline.game.GameBridge
import leyline.match.MatchDebugSink
import leyline.match.MatchEventType
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Adapts [DebugCollector] + [GameStateCollector] to the [MatchDebugSink] interface
 * so match code doesn't depend on the debug package directly.
 *
 * Created in [LeylineServer][leyline.infra.LeylineServer] and passed to [MatchHandler].
 */
class DebugSinkAdapter(
    private val debugCollector: DebugCollector,
    private val gameStateCollector: GameStateCollector,
) : MatchDebugSink {

    override fun recordInbound(msg: ClientToMatchServiceMessage) {
        debugCollector.recordInbound(msg)
    }

    override fun recordOutbound(messages: List<GREToClientMessage>, seatId: Int) {
        debugCollector.recordOutbound(messages, seatId)
    }

    override fun currentSeq(): Int = debugCollector.currentSeq()

    override fun clear() {
        debugCollector.clear()
    }

    override fun collectOutbound(messages: List<GREToClientMessage>, msgSeq: Int) {
        gameStateCollector.collectOutbound(messages, msgSeq)
    }

    override fun recordEvent(
        gsId: Int,
        type: MatchEventType,
        phase: String?,
        turn: Int,
        detail: String,
        priorityPlayer: String?,
        stackDepth: Int,
        msgSeq: Int,
    ) {
        gameStateCollector.recordEvent(gsId, type, phase, turn, detail, priorityPlayer, stackDepth, msgSeq)
    }

    override fun clearState() {
        gameStateCollector.clear()
    }

    @Suppress("UNCHECKED_CAST")
    override var bridgeProvider: (() -> Map<String, Any>)?
        get() = debugCollector.bridgeProvider?.let { provider ->
            { provider() as Map<String, Any> }
        }
        set(value) {
            debugCollector.bridgeProvider = value?.let { provider ->
                { provider() as Map<String, GameBridge> }
            }
        }

    override var sessionProvider: (() -> Any?)?
        get() = debugCollector.sessionProvider
        set(value) {
            debugCollector.sessionProvider = value
        }
}
