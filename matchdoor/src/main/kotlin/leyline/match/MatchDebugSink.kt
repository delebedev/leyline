package leyline.match

import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Debug/diagnostics contract for match sessions — decouples match code from
 * the concrete [DebugCollector][leyline.debug.DebugCollector] and
 * [GameStateCollector][leyline.debug.GameStateCollector] implementations.
 *
 * Production: wired by [LeylineServer][leyline.infra.LeylineServer] to the real collectors.
 * Tests: null (no debug panel).
 */
interface MatchDebugSink {

    // --- Protocol message collection (DebugCollector) ---

    /** Record an inbound client→server message. */
    fun recordInbound(msg: ClientToMatchServiceMessage)

    /** Record outbound server→client GRE messages. */
    fun recordOutbound(messages: List<GREToClientMessage>, seatId: Int)

    /** Current message sequence number (for cross-referencing timelines). */
    fun currentSeq(): Int

    /** Clear all collected data (on match reset). */
    fun clear()

    // --- Structured game state collection (GameStateCollector) ---

    /** Collect game state snapshots from outbound GRE messages. */
    fun collectOutbound(messages: List<GREToClientMessage>, msgSeq: Int)

    /** Record a priority/engine trace event. */
    fun recordEvent(
        gsId: Int,
        type: MatchEventType,
        phase: String?,
        turn: Int,
        detail: String,
        priorityPlayer: String?,
        stackDepth: Int,
        msgSeq: Int,
    )

    /** Clear game state collector data (on match reset). */
    fun clearState()

    // --- Wiring (set by MatchHandler.init) ---

    /** Provider for active game bridges. Set during handler init. */
    var bridgeProvider: (() -> Map<String, Any>)?

    /** Provider for the active seat-1 session (for debug injection). */
    var sessionProvider: (() -> Any?)?
}
