package leyline.match

import leyline.bridge.types.SeatId
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
) : SessionOps {
    override fun sendBundledGRE(messages: List<GREToClientMessage>) = sink.send(messages)

    override fun sendRealGameState(
        bridge: GameBridge,
        revealForSeat: Int?,
    ) {}

    override fun sendGameOver(reason: ResultReason) {}

    // Action methods: all inherited no-ops from ActionReceiver defaults.
    // SubmitAttackersReq/SubmitBlockersReq may arrive on the Familiar channel.
    // No-op is correct — the player's main session handles combat.
}
