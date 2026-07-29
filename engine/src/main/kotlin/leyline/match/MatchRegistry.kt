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

    /** matchId -> (seatId -> SessionOps). For cross-connection signaling. */
    private val sessions = ConcurrentHashMap<String, ConcurrentHashMap<Int, SessionOps>>()

    /** matchId -> (seatId -> MatchConnection). For pre-mulligan cross-connection messaging. */
    private val connections = ConcurrentHashMap<String, ConcurrentHashMap<Int, MatchConnection>>()

    /** matchId -> sole interactive execution owner, shared across reconnects and replacements. */
    private val owners = ConcurrentHashMap<String, MatchOwner>()

    internal fun ownerFor(matchId: String): MatchOwner =
        owners.computeIfAbsent(matchId) { id ->
            lateinit var owner: MatchOwner
            owner = MatchOwner(id) { owners.remove(id, owner) }
            owner
        }

    fun getOrCreateMatch(
        matchId: String,
        factory: () -> Match,
    ): Match {
        val owner = ownerFor(matchId)
        var result: Match? = null
        var created: Match? = null
        connections.compute(matchId) { _, current ->
            check(!owner.isClosed()) { "Match owner is closed" }
            result =
                matches.computeIfAbsent(matchId) {
                    factory().also { created = it }
                }
            current
        }
        created?.let { match ->
            match.bindWorkerFailure {
                log.error(
                    "MatchRegistry: engine worker failed matchId={} type={} message={}",
                    matchId,
                    it.failureType,
                    it.message,
                )
                teardownMatch(
                    matchId = matchId,
                    reason = MatchTeardownReason.EngineFailure,
                    expectedMatch = match,
                )
            }
        }
        val match = checkNotNull(result)
        check(matches[matchId] === match) {
            "Match worker failed during startup"
        }
        return match
    }

    /** Look up a match by id. */
    fun getMatch(matchId: String): Match? = matches[matchId]

    /** Convenience: get the bridge for a match directly. */
    fun getBridge(matchId: String): GameBridge? = matches[matchId]?.bridge

    fun registerSession(
        matchId: String,
        seatId: SeatId,
        session: SessionOps,
    ) {
        val owner = ownerFor(matchId)
        var previous: SessionOps? = null
        connections.compute(matchId) { _, current ->
            check(!owner.isClosed()) { "Match owner is closed" }
            previous = sessions.computeIfAbsent(matchId) { ConcurrentHashMap() }.put(seatId.value, session)
            current
        }
        previous?.let {
            if (it is SpectatorSession && it !== session) it.close()
            if (it is MatchSession && it !== session) it.close()
            if (it is FamiliarSession && it !== session) it.close()
        }
    }

    internal fun publishSessionAndConnection(
        matchId: String,
        expectedMatch: Match,
        seatId: SeatId,
        session: SessionOps,
        connection: MatchConnection,
        bind: () -> Unit,
    ) {
        val owner = ownerFor(matchId)
        var previousSession: SessionOps? = null
        var previousConnection: MatchConnection? = null
        connections.compute(matchId) { _, current ->
            check(!owner.isClosed()) { "Match owner is closed" }
            check(matches[matchId] === expectedMatch) {
                "Match generation is no longer active"
            }
            bind()
            previousSession = sessions.computeIfAbsent(matchId) { ConcurrentHashMap() }.put(seatId.value, session)
            (current ?: ConcurrentHashMap()).also {
                previousConnection = it.put(seatId.value, connection)
            }
        }
        previousSession?.let {
            if (it is SpectatorSession && it !== session) it.close()
            if (it is MatchSession && it !== session) it.close()
            if (it is FamiliarSession && it !== session) it.close()
        }
        previousConnection?.let {
            if (it !== connection) it.detachAfterTeardown()
        }
    }

    /** Get the OTHER seat's session (seat 1 -> seat 2, seat 2 -> seat 1). */
    fun getPeer(
        matchId: String,
        seatId: SeatId,
    ): SessionOps? {
        val peerSeat = SeatId(if (seatId.value == 1) 2 else 1)
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

    fun registerConnection(
        matchId: String,
        seatId: SeatId,
        connection: MatchConnection,
    ) {
        val owner = ownerFor(matchId)
        var previous: MatchConnection? = null
        connections.compute(matchId) { _, current ->
            check(!owner.isClosed()) { "Match owner is closed" }
            (current ?: ConcurrentHashMap()).also {
                previous = it.put(seatId.value, connection)
            }
        }
        previous?.let {
            if (it !== connection) it.detachAfterTeardown()
        }
    }

    fun getConnection(
        matchId: String,
        seatId: SeatId,
    ): MatchConnection? = connections[matchId]?.get(seatId.value)

    fun removeMatch(matchId: String): Match? = matches.remove(matchId)

    fun teardownMatch(
        matchId: String,
        reason: MatchTeardownReason,
        seatId: SeatId? = null,
        recorder: MatchRecorder? = null,
        fallbackBridge: GameBridge? = null,
        expectedConnection: MatchConnection? = null,
        expectedMatch: Match? = null,
    ) {
        log.info("MatchRegistry: teardown matchId={} seatId={} reason={}", matchId, seatId, reason)

        val owner = owners[matchId]
        val targetMatch = matches[matchId]
        var accepted = expectedConnection == null && (expectedMatch == null || targetMatch === expectedMatch)
        var matchConnections: Collection<MatchConnection> = emptyList()
        connections.compute(matchId) { _, current ->
            if (expectedConnection != null && current?.get(seatId?.value) !== expectedConnection) {
                return@compute current
            }
            if (expectedMatch != null && targetMatch !== expectedMatch) {
                return@compute current
            }
            accepted = true
            matchConnections = current?.values?.toList().orEmpty()
            if (owner == null) {
                targetMatch?.finish()
            } else {
                owner.close { targetMatch?.finish() }
            }
            null
        }
        if (!accepted) {
            log.info("MatchRegistry: ignored teardown from displaced connection matchId={} seatId={}", matchId, seatId)
            return
        }

        recorder?.shutdown()

        val removedSessions = sessions.remove(matchId)?.values.orEmpty()
        val sessionsRemoved = removedSessions.size
        val match = targetMatch?.takeIf { matches.remove(matchId, it) }

        val interactiveSessions = removedSessions.filterIsInstance<MatchSession>()
        interactiveSessions.forEach { it.retireBeforeOwnerClose() }
        removedSessions.filterIsInstance<SpectatorSession>().forEach { it.close() }
        removedSessions.filterIsInstance<FamiliarSession>().forEach { it.close() }
        matchConnections.forEach {
            if (reason == MatchTeardownReason.EngineFailure) {
                it.closeAfterTeardown()
            } else {
                it.detachAfterTeardown()
            }
        }

        val workerStop =
            if (match != null) {
                match.stop()
            } else {
                fallbackBridge?.shutdown()
            }

        owner?.awaitTermination()
        interactiveSessions.forEach { it.finishRetirementAfterOwnerClose() }

        log.info(
            "MatchRegistry: teardown complete matchId={} seatId={} reason={} " +
                "sessionsRemoved={} connectionsRemoved={} matchClosed={} workerStop={}",
            matchId,
            seatId,
            reason,
            sessionsRemoved,
            matchConnections.size,
            match != null || fallbackBridge != null,
            workerStop,
        )
    }

    /** Get seat 1 MatchSession for any active match (for debug injection). */
    fun activeSession(): MatchSession? =
        sessions.values
            .firstOrNull()
            ?.values
            ?.filterIsInstance<MatchSession>()
            ?.firstOrNull()
}
