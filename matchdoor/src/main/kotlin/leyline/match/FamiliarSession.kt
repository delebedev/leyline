package leyline.match

import forge.game.Game
import leyline.game.BundleBuilder
import leyline.game.GameBridge
import leyline.game.MessageCounter
import leyline.infra.MessageSink
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Read-only mirror session for the Familiar (AI spectator seat).
 *
 * Receives mirrored GRE messages from the human player's [MatchSession]
 * via [sendBundledGRE]. All action handlers are inherited no-ops from
 * [SessionOps] — the Familiar never drives game logic.
 *
 * All action handlers are inherited no-ops from [SessionOps] —
 * the type system enforces read-only behavior without boolean gates.
 */
class FamiliarSession(
    override val seatId: Int,
    override val matchId: String,
    val sink: MessageSink,
    override var counter: MessageCounter = MessageCounter(),
) : SessionOps {

    override fun sendBundledGRE(messages: List<GREToClientMessage>) {
        sink.send(messages)
    }

    override fun sendRealGameState(bridge: GameBridge) {}
    override fun sendBundle(result: BundleBuilder.BundleResult) {}
    override fun sendGameOver(reason: ResultReason) {}
    override fun traceEvent(type: MatchEventType, game: Game, detail: String) {}
    override fun paceDelay(multiplier: Int) {}

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

    // Action methods: all inherited no-ops from SessionOps defaults.
    // SubmitAttackersReq/SubmitBlockersReq: client race condition may send
    // these on the Familiar channel. No-op is correct — the player's session
    // handles combat independently. PvP may want cross-seat routing later.
}
