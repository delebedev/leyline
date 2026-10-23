package leyline.match

import leyline.bridge.types.SeatId
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages shared matches and peer sessions across connections.
 * Replaces process-wide match state.
 *
 * Production: singleton instance. Tests: fresh per test.
 */
class MatchRegistry {
    private val log = LoggerFactory.getLogger(MatchRegistry::class.java)

    /** matchId -> shared Match. First seat creates, second reuses. */
    private val matches = ConcurrentHashMap<String, Match>()

    /** One attachment generation per seat, shared with its connection. */
    private val sessions = ConcurrentHashMap<String, ConcurrentHashMap<Int, Registration>>()

    inner class Registration internal constructor(
        session: SessionOps,
        internal val connection: MatchConnection?,
    ) {
        /** Lock-free observation of the one current session for this attachment. */
        @Volatile
        var session: SessionOps = session
            private set

        /** Reconstruction is serialized with attachment; host publication runs outside the key operation. */
        internal fun <T : Any> replace(
            expected: MatchSession,
            create: () -> MatchSession,
            publish: (MatchSession) -> T,
        ): T =
            synchronized(expected.connection.sessionLock) {
                var replacement: MatchSession? = null
                matches.compute(expected.matchId) { _, match ->
                    check(isCurrent(this) && session === expected && match?.bridge === expected.gameBridge) {
                        "Session registration has been superseded"
                    }
                    connection?.stopRuntimeDeliveryObserver()
                    val next = create()
                    check(next.connection === expected.connection) { "Session replacement must preserve connection ownership" }
                    session = next
                    replacement = next
                    match
                }
                val installed = checkNotNull(replacement)
                publish(installed).also {
                    matches.compute(expected.matchId) { _, match ->
                        if (isCurrent(this) && session === installed) connection?.armRuntimeDeliveryObserver()
                        match
                    }
                }
            }
    }

    fun getOrCreateMatch(
        matchId: String,
        factory: () -> Match,
    ): Match = matches.computeIfAbsent(matchId) { factory() }

    /** Look up a match by id. */
    fun getMatch(matchId: String): Match? = matches[matchId]

    fun registerSession(
        matchId: String,
        seatId: SeatId,
        session: SessionOps,
        connection: MatchConnection? = null,
    ): Registration = registerSession(matchId, seatId, connection) { session }

    /** Construct game-bound handlers only after any prior reconstruction. */
    internal fun registerSession(
        matchId: String,
        seatId: SeatId,
        connection: MatchConnection?,
        create: () -> SessionOps,
    ): Registration {
        var registration: Registration? = null
        var previous: SessionOps? = null
        matches.compute(matchId) { _, match ->
            val session = create()
            require(session.matchId == matchId && session.seatId == seatId) { "Session identity does not match its registration" }
            if (connection != null) {
                checkNotNull(match) { "Cannot attach a connection without its match" }
                val bridge = (session as? GameOps)?.gameBridge ?: (session as? SpectatorSession)?.gameBridge
                check(bridge == null || bridge === match.bridge) { "Cannot attach a session from a different match generation" }
            }
            registration =
                Registration(session, connection).also {
                    val prior = sessions.computeIfAbsent(matchId) { ConcurrentHashMap() }.put(seatId.value, it)
                    previous = prior?.session
                    prior?.connection?.stopRuntimeDeliveryObserver()
                    connection?.bindRegistration(it)
                }
            match
        }
        val installed = checkNotNull(registration)
        if (previous !== installed.session) {
            when (val prior = previous) {
                is MatchSession -> prior.close()
                is SpectatorSession -> prior.close()
            }
        }
        return installed
    }

    internal fun isCurrent(registration: Registration): Boolean {
        val session = registration.session
        return sessions[session.matchId]?.get(session.seatId.value) === registration
    }

    /** Guard destructive reconstruction and publication against reconnect and teardown. */
    internal fun <T : Any> replaceSession(
        expected: MatchSession,
        create: () -> MatchSession,
        publish: (MatchSession) -> T,
    ): T =
        checkNotNull(sessions[expected.matchId]?.get(expected.seatId.value)) { "Session is no longer registered" }
            .replace(expected, create, publish)

    /** Get the OTHER seat's session (seat 1 -> seat 2, seat 2 -> seat 1). */
    fun getPeer(
        matchId: String,
        seatId: SeatId,
    ): SessionOps? {
        val peerSeat = SeatId(if (seatId.value == 1) 2 else 1)
        return sessions[matchId]?.get(peerSeat.value)?.session
    }

    /** Return the active human session; Familiar registration never replaces it. */
    fun activeHumanSession(): MatchSession? =
        sessions.values
            .asSequence()
            .mapNotNull { it[1]?.session as? MatchSession }
            .firstOrNull()

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

    fun getConnection(
        matchId: String,
        seatId: SeatId,
    ): MatchConnection? = sessions[matchId]?.get(seatId.value)?.connection

    fun removeMatch(matchId: String): Match? = matches.remove(matchId)

    /** An expected bridge limits registry detachment to that exact match generation. */
    fun teardownMatch(
        matchId: String,
        reason: MatchTeardownReason,
        seatId: SeatId? = null,
        fallbackBridge: GameBridge? = null,
        expectedBridge: GameBridge? = null,
        expectedRegistration: Registration? = null,
    ) {
        var removedRegistrations: List<Registration> = emptyList()
        var match: Match? = null
        var supersededRegistration = false
        // Generation admission and detachment share the match key's existing creation lock.
        // Only connection binding/observer invalidation detaches here. Worker and
        // bridge cleanup stays outside the key operation and never waits for sessionLock.
        matches.compute(matchId) { _, current ->
            if (expectedRegistration != null && sessions[matchId]?.get(seatId?.value) !== expectedRegistration) {
                supersededRegistration = true
                current
            } else if (expectedBridge != null && current?.bridge !== expectedBridge) {
                current
            } else {
                removedRegistrations =
                    sessions
                        .remove(matchId)
                        ?.values
                        ?.toList()
                        .orEmpty()
                removedRegistrations.forEach { it.connection?.detachAfterTeardown(it) }
                match = current
                null
            }
        }
        if (supersededRegistration) return
        val sessionsRemoved = removedRegistrations.size
        val removedMatch = match

        removedRegistrations.forEach { registration ->
            when (val session = registration.session) {
                is MatchSession -> session.close()
                is SpectatorSession -> session.close()
            }
        }

        if (removedMatch != null) {
            removedMatch.close()
        } else {
            fallbackBridge?.shutdown()
        }

        val event =
            log
                .atInfo()
                .addKeyValue("event", "match.teardown")
                .addKeyValue("match_id", matchId)
        val seatedEvent = seatId?.value?.let { event.addKeyValue("seat", it) } ?: event
        seatedEvent
            .addKeyValue("reason", reason.name)
            .addKeyValue("sessions_removed", sessionsRemoved)
            .addKeyValue("connections_removed", removedRegistrations.count { it.connection != null })
            .addKeyValue("match_closed", removedMatch != null || fallbackBridge != null)
            .log("Match torn down")
    }
}
