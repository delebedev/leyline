package leyline.match

import leyline.bridge.types.ClientAutoPassState
import leyline.bridge.types.SeatId
import leyline.frontdoor.service.MatchCoordinator
import leyline.infra.MessageSink
import wotc.mtgo.gre.external.messaging.Messages.SettingsMessage

/**
 * Per-channel state that survives game-level lifecycle events (puzzle hot-swap,
 * game-end). Owned by [MatchHandler]; referenced by [MatchSession].
 *
 * Identity, transport, and client-driven settings live here so they remain
 * stable when the underlying [forge.game.Game] is replaced.
 */
class ConnectionState(
    val seatId: SeatId,
    val matchId: String,
    val sink: MessageSink,
    val registry: MatchRegistry,
    val recorder: MatchRecorder? = null,
    val coordinator: MatchCoordinator? = null,
) {
    /** Client player ID — set by MatchHandler after auth, used in MatchCompleted room state. */
    var playerId: String = "forge-player-1"

    /** Saved client settings for echoing in SetSettingsResp. */
    var clientSettings: SettingsMessage? = null

    /** Client auto-pass settings (autoPassOption / stackAutoPassOption). */
    val autoPassState = ClientAutoPassState()
}
