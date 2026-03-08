package leyline.frontdoor.service

import org.slf4j.LoggerFactory

/**
 * FIFO matchmaking queue — pairs first two 603 requests.
 *
 * Thread-safe: two FD connections arrive on different Netty I/O threads.
 * Synchronized for simplicity — max 2 concurrent callers.
 *
 * When [syntheticOpponent] is true, the queue auto-pairs immediately with
 * a no-op bot entry instead of waiting for a second player. The match still
 * goes through the full queue/pairing flow, but MD uses its normal AI path.
 */
class MatchmakingQueue(
    private val syntheticOpponent: Boolean = false,
) {
    private val log = LoggerFactory.getLogger(MatchmakingQueue::class.java)

    @Volatile
    private var waiting: PairingEntry? = null

    /**
     * Attempt to pair this player. Returns [PairResult.Waiting] if first in queue,
     * or [PairResult.Paired] if a partner was already waiting (or synthetic mode).
     */
    @Synchronized
    fun pair(entry: PairingEntry): PairResult {
        val partner = waiting
        if (partner != null) {
            waiting = null
            val matchId = java.util.UUID.randomUUID().toString()
            log.info("MatchmakingQueue: paired {} vs {} → matchId={}", partner.screenName, entry.screenName, matchId)
            return PairResult.Paired(seat1 = partner, seat2 = entry, matchId = matchId)
        }
        if (syntheticOpponent) {
            val matchId = java.util.UUID.randomUUID().toString()
            val bot = PairingEntry("SyntheticBot") { _, _ -> }
            log.info("MatchmakingQueue: auto-paired {} vs SyntheticBot → matchId={}", entry.screenName, matchId)
            return PairResult.Paired(seat1 = entry, seat2 = bot, matchId = matchId, synthetic = true)
        }
        waiting = entry
        log.info("MatchmakingQueue: {} waiting for opponent", entry.screenName)
        return PairResult.Waiting
    }

    /** Remove waiting player (cancel queue). Returns true if someone was removed. */
    @Synchronized
    fun cancel(screenName: String): Boolean {
        if (waiting?.screenName == screenName) {
            log.info("MatchmakingQueue: {} cancelled", screenName)
            waiting = null
            return true
        }
        return false
    }

    /** Check if anyone is waiting (test helper). */
    @Synchronized
    fun hasWaiting(): Boolean = waiting != null
}

data class PairingEntry(
    val screenName: String,
    /** Opaque callback for pushing MatchCreated back to this FD channel. */
    val pushCallback: (matchId: String, yourSeat: Int) -> Unit,
)

sealed class PairResult {
    data object Waiting : PairResult()
    data class Paired(
        val seat1: PairingEntry,
        val seat2: PairingEntry,
        val matchId: String,
        /** True when seat 2 is a synthetic bot (MD uses normal AI, not startTwoPlayer). */
        val synthetic: Boolean = false,
    ) : PairResult()
}
