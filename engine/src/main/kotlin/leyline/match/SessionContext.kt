package leyline.match

import leyline.game.state.GameBridge

/**
 * Resolved session state -- non-null after bridge connection.
 * Constructed once per handler dispatch inside the synchronized block.
 */
data class SessionContext(
    val bridge: GameBridge,
    val engine: EngineCutAwaiter,
)
