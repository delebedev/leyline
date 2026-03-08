package leyline.match

import leyline.game.GameBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages shared matches and peer sessions across connections.
 * Replaces MatchHandler.Companion static maps.
 *
 * Production: singleton instance. Tests: fresh per test.
 */
class MatchRegistry {

    /** matchId -> shared Match. First seat creates, second reuses. */
    private val matches = ConcurrentHashMap<String, Match>()

    /** matchId -> (seatId -> MatchSession). For cross-connection signaling. */
    private val sessions = ConcurrentHashMap<String, ConcurrentHashMap<Int, MatchSession>>()

    /** matchId -> (seatId -> MatchHandler). For pre-mulligan cross-connection messaging. */
    private val handlers = ConcurrentHashMap<String, ConcurrentHashMap<Int, MatchHandler>>()

    fun getOrCreateMatch(matchId: String, factory: () -> Match): Match =
        matches.computeIfAbsent(matchId) { factory() }

    /** Look up a match by id. */
    fun getMatch(matchId: String): Match? = matches[matchId]

    /** Convenience: get the bridge for a match directly. */
    fun getBridge(matchId: String): GameBridge? = matches[matchId]?.bridge

    fun registerSession(matchId: String, seatId: Int, session: MatchSession) {
        sessions.computeIfAbsent(matchId) { ConcurrentHashMap() }[seatId] = session
    }

    /** Get the OTHER seat's session (seat 1 -> seat 2, seat 2 -> seat 1). */
    fun getPeer(matchId: String, seatId: Int): MatchSession? {
        val peerSeat = if (seatId == 1) 2 else 1
        return sessions[matchId]?.get(peerSeat)
    }

    /**
     * Remove all matches and sessions except [currentMatchId].
     * Returns list of evicted matches (already closed).
     */
    fun evictStale(currentMatchId: String): List<Match> {
        val staleKeys = matches.keys.filter { it != currentMatchId }
        val evicted = staleKeys.mapNotNull { matches.remove(it) }
        staleKeys.forEach {
            sessions.remove(it)
            handlers.remove(it)
        }
        evicted.forEach { it.close() }
        return evicted
    }

    fun registerHandler(matchId: String, seatId: Int, handler: MatchHandler) {
        handlers.computeIfAbsent(matchId) { ConcurrentHashMap() }[seatId] = handler
    }

    fun getHandler(matchId: String, seatId: Int): MatchHandler? =
        handlers[matchId]?.get(seatId)

    fun removeMatch(matchId: String): Match? = matches.remove(matchId)

    /** Snapshot of all active bridges (for debug panel). */
    fun activeBridges(): Map<String, GameBridge> =
        HashMap(matches).mapValues { it.value.bridge }

    /** Get seat 1 session for any active match (for debug injection). */
    fun activeSession(): MatchSession? =
        sessions.values.firstOrNull()?.get(1)
}
