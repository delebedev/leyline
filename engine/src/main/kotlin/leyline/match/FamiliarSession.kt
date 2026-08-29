package leyline.match

import leyline.bridge.types.SeatId
import leyline.game.state.GameBridge
import leyline.infra.MessageSink
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Read-only viewer session for the Familiar (AI spectator seat).
 *
 * Drains its coordinator-committed viewer feed. The Familiar never drives game
 * logic.
 *
 * All action handlers are inherited no-ops from [SessionOps] —
 * the type system enforces read-only behavior without boolean gates.
 */
class FamiliarSession(
    override val seatId: SeatId,
    override val matchId: String,
    val sink: MessageSink,
    val gameBridge: GameBridge? = null,
) : SessionOps {
    override fun sendBundledGRE(messages: List<GREToClientMessage>) = sink.send(messages)

    override fun sendRealGameState(
        bridge: GameBridge,
        revealForSeat: Int?,
    ) = deliverCommitted()

    internal fun deliverCommitted() {
        gameBridge?.let { deliverCommittedCoordinatorBatches(this, it, seatId) }
    }

    override fun sendGameOver() {}

    // Action methods: all inherited no-ops from ActionReceiver defaults.
    // SubmitAttackersReq/SubmitBlockersReq may arrive on the Familiar channel.
    // No-op is correct — the player's main session handles combat.
}
