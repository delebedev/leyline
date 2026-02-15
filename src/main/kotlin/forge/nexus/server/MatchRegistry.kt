package forge.nexus.server

import forge.nexus.game.GameBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages shared bridges and peer sessions across connections.
 * Replaces MatchHandler.Companion static maps.
 *
 * Production: singleton instance. Tests: fresh per test.
 */
class MatchRegistry {

    /** matchId -> shared GameBridge. First seat creates, second reuses. */
    private val bridges = ConcurrentHashMap<String, GameBridge>()

    /** matchId -> (seatId -> MatchSession). For cross-connection signaling. */
    private val sessions = ConcurrentHashMap<String, ConcurrentHashMap<Int, MatchSession>>()

    fun getOrCreateBridge(matchId: String, factory: () -> GameBridge): GameBridge =
        bridges.computeIfAbsent(matchId) { factory() }

    fun registerSession(matchId: String, seatId: Int, session: MatchSession) {
        sessions.computeIfAbsent(matchId) { ConcurrentHashMap() }[seatId] = session
    }

    /** Get the OTHER seat's session (seat 1 -> seat 2, seat 2 -> seat 1). */
    fun getPeer(matchId: String, seatId: Int): MatchSession? {
        val peerSeat = if (seatId == 1) 2 else 1
        return sessions[matchId]?.get(peerSeat)
    }

    /**
     * Remove all bridges and sessions except [currentMatchId].
     * Returns list of evicted bridges (caller should shutdown).
     */
    fun evictStale(currentMatchId: String): List<GameBridge> {
        val staleKeys = bridges.keys.filter { it != currentMatchId }
        val evicted = staleKeys.mapNotNull { bridges.remove(it) }
        staleKeys.forEach { sessions.remove(it) }
        return evicted
    }

    fun removeBridge(matchId: String): GameBridge? = bridges.remove(matchId)
}
