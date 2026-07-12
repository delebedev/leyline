package leyline.match

/**
 * Debug server wiring — provides session access for puzzle injection and best-play recommendations.
 *
 * Production: wired by [LeylineServer][leyline.infra.LeylineServer].
 * Tests: null (no debug controls).
 */
interface MatchDebugSink {
    /** Provider for the active seat-1 session (for debug injection). */
    var sessionProvider: (() -> Any?)?
}
