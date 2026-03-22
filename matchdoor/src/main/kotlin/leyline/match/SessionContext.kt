package leyline.match

import forge.game.Game
import leyline.game.GameBridge

/**
 * Resolved session state -- non-null after bridge connection.
 * Constructed once per handler dispatch inside the synchronized block.
 */
data class SessionContext(
    val game: Game,
    val bridge: GameBridge,
)
