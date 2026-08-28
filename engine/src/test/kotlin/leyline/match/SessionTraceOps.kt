package leyline.match

import leyline.bridge.types.SeatId
import leyline.game.state.GameBridge
import leyline.match.GameOps
import leyline.match.SessionContext
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Test double for [GameOps] that traces all calls for assertion.
 *
 * Always constructed with a [GameBridge] — handlers under test need
 * a non-null `gameBridge`.
 */
class SessionTraceOps(
    override val seatId: SeatId = SeatId(1),
    override val matchId: String = "test-match",
    override val gameBridge: GameBridge,
) : GameOps {
    /** Snapshot for handler construction in tests. */
    val ctx: SessionContext = SessionContext(requireNotNull(gameBridge.getGame()) { "SessionTraceOps requires non-null game" }, gameBridge)

    // --- Traced calls ---

    val sentGRE = mutableListOf<List<GREToClientMessage>>()
    val sentRealGameState = mutableListOf<GameBridge>()
    val sentGameOver = mutableListOf<ResultReason>()
    val sendRealGameStateCount: Int get() = sentRealGameState.size
    val sendGameOverCount: Int get() = sentGameOver.size

    override fun sendBundledGRE(messages: List<GREToClientMessage>) {
        sentGRE.add(messages)
    }

    override fun sendRealGameState(
        bridge: GameBridge,
        revealForSeat: Int?,
    ) {
        sentRealGameState.add(bridge)
    }

    override fun sendGameOver(reason: ResultReason) {
        sentGameOver.add(reason)
    }
}
