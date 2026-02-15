package forge.nexus.server

import forge.nexus.game.GameBridge

/**
 * Game orchestration session — all game logic extracted from MatchHandler.
 *
 * Stub — will be populated in Task 3.
 */
class MatchSession(
    val seatId: Int,
    val matchId: String,
    val sink: MessageSink,
    val registry: MatchRegistry,
    val paceDelayMs: Long = 200L,
) {
    var msgIdCounter = 1
    var gameStateId = 0
    var gameBridge: GameBridge? = null
}
