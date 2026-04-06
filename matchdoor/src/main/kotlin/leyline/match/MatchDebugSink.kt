package leyline.match

/**
 * Debug server wiring — provides session/bridge access to the debug HTTP
 * server for puzzle injection, best-play oracle, priority log, etc.
 *
 * Production: wired by [LeylineServer][leyline.infra.LeylineServer].
 * Tests: null (no debug panel).
 */
interface MatchDebugSink {

    /** Provider for active game bridges. Set during handler init. */
    var bridgeProvider: (() -> Map<String, Any>)?

    /** Provider for the active seat-1 session (for debug injection). */
    var sessionProvider: (() -> Any?)?
}
