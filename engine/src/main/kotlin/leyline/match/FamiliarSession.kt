package leyline.match

import leyline.bridge.types.SeatId
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.MessageCounter
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
internal class FamiliarSession(
    override val seatId: SeatId,
    override val matchId: String,
    val sink: MessageSink,
    override var counter: MessageCounter = MessageCounter(),
    private val owner: MatchOwner = MatchOwner(matchId),
) : SessionOps {
    internal val protocolHead =
        MatchProtocolHead(
            owner,
            MatchOutbox.Audience.Familiar(seatId),
            sink,
        )

    override fun sendBundledGRE(messages: List<GREToClientMessage>) =
        owner.reduce {
            for (m in messages) {
                if (m.hasGameStateMessage()) counter.markGameStateGsId(m.gameStateMessage.gameStateId)
            }
            owner.observeOutbound(messages)
            owner.appendOutbox(listOf(protocolHead.token to MatchOutbox.Payload.Gre(messages)))
            protocolHead.flush()
        }

    override fun sendMatchProgress(message: MatchServiceToClientMessage) =
        owner.reduce {
            owner.observeOutbound(message)
            owner.appendOutbox(listOf(protocolHead.token to MatchOutbox.Payload.Raw(message)))
            protocolHead.flush()
        }

    fun close() = protocolHead.close()

    override fun sendRealGameState(
        bridge: GameBridge,
        revealForSeat: Int?,
    ) {}

    override fun sendBundle(result: BundleBuilder.BundleResult) {}

    override fun sendGameOver(reason: ResultReason) {}

    override fun paceDelay(multiplier: Int) {}

    // Action methods: all inherited no-ops from ActionReceiver defaults.
    // SubmitAttackersReq/SubmitBlockersReq may arrive on the Familiar channel.
    // No-op is correct — the player's main session handles combat.
}
