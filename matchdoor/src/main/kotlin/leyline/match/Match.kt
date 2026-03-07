package leyline.match

import leyline.game.GameBridge

/**
 * Owns the full lifecycle of a single game match.
 * Phase 1: thin wrapper around GameBridge — delegates everything.
 */
class Match(
    val matchId: String,
    val bridge: GameBridge,
) {
    fun start(
        seed: Long? = null,
        deckList: String? = null,
        deckList1: String? = null,
        deckList2: String? = null,
    ) = bridge.start(seed, deckList, deckList1, deckList2)

    fun shutdown() = bridge.shutdown()
}
