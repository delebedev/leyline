package leyline.match

import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Recording contract for match sessions.
 * Currently unused (no recorder wired). Retained for future external recording tools.
 */
interface MatchRecorder {
    /** Record outbound GRE messages (what we told the client). */
    fun recordOutbound(messages: List<GREToClientMessage>)

    /** Record an inbound client action. */
    fun recordClientAction(greMsg: ClientToGREMessage)

    /** Mark that a game-over was received (for analysis). */
    fun markGameOver()

    /** Close the recorder and release resources. Idempotent. */
    fun shutdown()
}
