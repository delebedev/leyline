package leyline.match

import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Trace contract for match sessions.
 * Currently unused (no tracer wired). Retained for future external tooling.
 */
interface MatchRecorder {
    /** Trace outbound GRE messages (what we told the client). */
    fun recordOutbound(messages: List<GREToClientMessage>)

    /** Trace an inbound client action. */
    fun recordClientAction(greMsg: ClientToGREMessage)

    /** Mark that a game-over was received. */
    fun markGameOver()

    /** Close the recorder and release resources. Idempotent. */
    fun shutdown()
}
