package leyline.match

import leyline.bridge.types.SeatId
import leyline.bridge.types.opponent
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages shared matches and peer sessions across connections.
 * Replaces MatchHandler.Companion static maps.
 *
 * Production: singleton instance. Tests: fresh per test.
 */
class MatchRegistry {
    private val log = LoggerFactory.getLogger(MatchRegistry::class.java)

    /** matchId -> shared Match. First seat creates, second reuses. */
    private val matches = ConcurrentHashMap<String, Match>()

    /** matchId -> (seatId -> SessionOps). For cross-connection signaling. */
    private val sessions = ConcurrentHashMap<String, ConcurrentHashMap<Int, SessionOps>>()

    /** matchId -> (seatId -> MatchHandler). For pre-mulligan cross-connection messaging. */
    private val handlers = ConcurrentHashMap<String, ConcurrentHashMap<Int, MatchHandler>>()

    fun getOrCreateMatch(
        matchId: String,
        factory: () -> Match,
    ): Match = matches.computeIfAbsent(matchId) { factory() }

    /** Look up a match by id. */
    fun getMatch(matchId: String): Match? = matches[matchId]

    /** Convenience: get the bridge for a match directly. */
    fun getBridge(matchId: String): GameBridge? = matches[matchId]?.bridge

    fun registerSession(
        matchId: String,
        seatId: SeatId,
        session: SessionOps,
    ) {
        sessions.computeIfAbsent(matchId) { ConcurrentHashMap() }[seatId.value] = session
    }

    /** Get the OTHER seat's session (seat 1 -> seat 2, seat 2 -> seat 1). */
    fun getPeer(
        matchId: String,
        seatId: SeatId,
    ): SessionOps? {
        val peerSeat = seatId.opponent
        return sessions[matchId]?.get(peerSeat.value)
    }

    /**
     * Remove all matches and sessions except [currentMatchId].
     * Returns list of evicted matches (already closed).
     */
    fun evictStale(currentMatchId: String): List<Match> {
        val staleKeys = matches.keys.filter { it != currentMatchId }
        val evicted = staleKeys.mapNotNull { matches[it] }
        staleKeys.forEach { teardownMatch(it, MatchTeardownReason.Disconnect) }
        return evicted
    }

    fun registerHandler(
        matchId: String,
        seatId: SeatId,
        handler: MatchHandler,
    ) {
        handlers.computeIfAbsent(matchId) { ConcurrentHashMap() }[seatId.value] = handler
    }

    fun getHandler(
        matchId: String,
        seatId: SeatId,
    ): MatchHandler? = handlers[matchId]?.get(seatId.value)

    fun removeMatch(matchId: String): Match? = matches.remove(matchId)

    fun teardownMatch(
        matchId: String,
        reason: MatchTeardownReason,
        seatId: SeatId? = null,
        recorder: MatchRecorder? = null,
        fallbackBridge: GameBridge? = null,
    ) {
        log.info("MatchRegistry: teardown matchId={} seatId={} reason={}", matchId, seatId, reason)

        recorder?.shutdown()

        val matchHandlers = handlers.remove(matchId)?.values.orEmpty()
        val removedSessions = sessions.remove(matchId)?.values.orEmpty()
        val sessionsRemoved = removedSessions.size
        val match = matches.remove(matchId)

        removedSessions.filterIsInstance<MatchSession>().forEach { it.close() }
        matchHandlers.forEach { it.detachAfterTeardown() }

        if (match != null) {
            match.close()
        } else {
            fallbackBridge?.shutdown()
        }

        log.info(
            "MatchRegistry: teardown complete matchId={} seatId={} reason={} sessionsRemoved={} handlersRemoved={} matchClosed={}",
            matchId,
            seatId,
            reason,
            sessionsRemoved,
            matchHandlers.size,
            match != null || fallbackBridge != null,
        )
    }

    /** Snapshot of all active bridges (for debug panel). */
    fun activeBridges(): Map<String, GameBridge> = HashMap(matches).mapValues { it.value.bridge }

    /** Get seat 1 MatchSession for any active match (for debug injection). */
    fun activeSession(): MatchSession? =
        sessions.values
            .firstOrNull()
            ?.values
            ?.filterIsInstance<MatchSession>()
            ?.firstOrNull()
}
