package leyline.frontdoor.service

import org.slf4j.LoggerFactory

/**
 * FIFO matchmaking queue — pairs first two 603 requests.
 *
 * Thread-safe: two FD connections arrive on different Netty I/O threads.
 * Synchronized for simplicity — max 2 concurrent callers.
 */
class MatchmakingQueue {
    private val log = LoggerFactory.getLogger(MatchmakingQueue::class.java)

    @Volatile
    private var waiting: PairingEntry? = null

    /**
     * Attempt to pair this player. Returns [PairResult.Waiting] if first in queue,
     * or [PairResult.Paired] if a partner was already waiting.
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
    data class Paired(val seat1: PairingEntry, val seat2: PairingEntry, val matchId: String) : PairResult()
}
