package leyline.unit

import forge.game.Game
import leyline.game.BundleBuilder
import leyline.game.GameBridge
import leyline.game.MessageCounter
import leyline.match.MatchEventType
import leyline.match.SessionOps
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Test double for [SessionOps] that records all calls for assertion.
 *
 * Provides a real [BundleBuilder] when constructed with a [GameBridge],
 * or null by default (for tests that don't need action building).
 */
class RecordingSessionOps(
    override val seatId: Int = 1,
    override val matchId: String = "test-match",
    override var counter: MessageCounter = MessageCounter(),
    private val bridge: GameBridge? = null,
) : SessionOps {

    override val bundleBuilder: BundleBuilder? =
        bridge?.let { BundleBuilder(it, matchId, seatId) }

    // --- Recorded calls ---

    val sentGRE = mutableListOf<List<GREToClientMessage>>()
    val sentRealGameState = mutableListOf<GameBridge>()
    val sentGameOver = mutableListOf<ResultReason>()
    val tracedEvents = mutableListOf<Triple<MatchEventType, String, String>>()
    val paceDelays = mutableListOf<Int>()

    val sendRealGameStateCount: Int get() = sentRealGameState.size
    val sendGameOverCount: Int get() = sentGameOver.size

    override fun sendBundledGRE(messages: List<GREToClientMessage>) {
        sentGRE.add(messages)
    }

    override fun sendRealGameState(bridge: GameBridge, revealForSeat: Int?) {
        sentRealGameState.add(bridge)
    }

    override fun sendBundle(result: BundleBuilder.BundleResult) {
        sentGRE.add(result.messages)
    }

    override fun sendGameOver(reason: ResultReason) {
        sentGameOver.add(reason)
    }

    override fun traceEvent(type: MatchEventType, game: Game, detail: String) {
        tracedEvents.add(Triple(type, game.phaseHandler.phase?.name ?: "?", detail))
    }

    override fun paceDelay(multiplier: Int) {
        paceDelays.add(multiplier)
    }

    override fun makeGRE(
        type: GREMessageType,
        gsId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage {
        val gre = GREToClientMessage.newBuilder()
            .setType(type).setMsgId(msgId).setGameStateId(gsId).addSystemSeatIds(seatId)
        configure(gre)
        return gre.build()
    }

    /** True if any traced event has the given type. */
    fun hasTrace(type: MatchEventType): Boolean =
        tracedEvents.any { it.first == type }

    /** True if any traced event detail contains the substring. */
    fun hasTraceContaining(detail: String): Boolean =
        tracedEvents.any { it.third.contains(detail) }
}
