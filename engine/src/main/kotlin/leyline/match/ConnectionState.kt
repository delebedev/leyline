package leyline.match

import leyline.bridge.types.SeatId
import leyline.domain.service.MatchCoordinator
import leyline.infra.MessageSink

/**
 * Per-channel state that survives game-level lifecycle events (puzzle hot-swap,
 * game-end). Owned by [MatchHandler]; referenced by [MatchSession].
 *
 * Identity and transport live here so they remain stable when the underlying
 * [forge.game.Game] is replaced. Client-driven priority settings live on the
 * match-scoped engine runtime.
 */
class ConnectionState(
    val seatId: SeatId,
    val matchId: String,
    val sink: MessageSink,
    val registry: MatchRegistry,
    val coordinator: MatchCoordinator? = null,
) {
    /** Client player ID — set by MatchHandler after auth, used in MatchCompleted room state. */
    var playerId: String = "forge-player-1"

    /**
     * Serializes all game-logic entry points (Netty I/O threads are concurrent).
     *
     * Lives on the connection — must outlive game-level recreation (puzzle hot-swap)
     * so concurrent inbound messages can't race the swap.
     */
    val sessionLock = Any()
}
