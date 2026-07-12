package leyline.debug

import leyline.match.MatchDebugSink

/**
 * Minimal [MatchDebugSink] implementation — holds the session provider
 * so the debug server can access live engine state for puzzle injection,
 * best-play output.
 *
 * Other sink hooks keep the default no-op implementations.
 */
class DebugSinkAdapter : MatchDebugSink {
    override var sessionProvider: (() -> Any?)? = null
}
