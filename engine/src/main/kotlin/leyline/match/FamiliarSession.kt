package leyline.match

import leyline.bridge.types.SeatId
import leyline.game.bundle.MessageCounter
import leyline.game.bundle.markIfPrompt
import leyline.game.state.GameBridge
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
    override val seatId: SeatId,
    override val matchId: String,
    val sink: MessageSink,
    override var counter: MessageCounter = MessageCounter(),
) : SessionOps {
    override fun sendBundledGRE(messages: List<GREToClientMessage>) {
        for (m in messages) {
            if (m.hasGameStateMessage()) counter.markGameStateGsId(m.gameStateMessage.gameStateId)
            markIfPrompt(counter, m.type, m.gameStateId, m.msgId)
        }
        sink.send(messages)
    }

    override fun sendRealGameState(
        bridge: GameBridge,
        revealForSeat: Int?,
    ) {}

    override fun sendGameOver(reason: ResultReason) {}

    // Action methods: all inherited no-ops from ActionReceiver defaults.
    // SubmitAttackersReq/SubmitBlockersReq may arrive on the Familiar channel.
    // No-op is correct — the player's main session handles combat.
}
