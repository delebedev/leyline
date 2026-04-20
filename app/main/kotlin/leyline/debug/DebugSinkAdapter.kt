package leyline.debug

import leyline.match.MatchDebugSink

/**
 * Minimal [MatchDebugSink] implementation — holds session/bridge providers
 * so the debug server can access live engine state for puzzle injection,
 * best-play output, and priority log.
 *
 * Other sink hooks keep the default no-op implementations.
 */
class DebugSinkAdapter : MatchDebugSink {
    override var bridgeProvider: (() -> Map<String, Any>)? = null

    override var sessionProvider: (() -> Any?)? = null
}
