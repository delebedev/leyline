package leyline.game

import forge.game.Game
import leyline.bridge.GameActionBridge
import leyline.bridge.InteractivePromptBridge
import leyline.bridge.PlayerAction

/**
 * Shared test helpers for GameBridge-based tests.
 *
 * Extracted from ConformanceTestBase so both conformance and
 * integration tests use the same race-free implementations.
 */

/**
 * Convenience: build a full GSM snapshot from the live game and store it.
 * Production code passes pre-built GSMs to [GameBridge.snapshotState] directly;
 * this wrapper avoids boilerplate in tests.
 */
fun GameBridge.snapshotFromGame(game: Game, gameStateId: Int = 0) {
    snapshotState(StateMapper.buildFromGame(game, gameStateId, "", this))
}

/**
 * Wait for a pending action whose actionId differs from [previousId].
 * Returns null on timeout (default 15s).
 */
fun awaitFreshPending(
    b: GameBridge,
    previousId: String?,
    timeoutMs: Long = 15_000,
): GameActionBridge.PendingAction? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val p = b.actionBridge.getPending()
        if (p != null && p.actionId != previousId && !p.future.isDone) return p
        Thread.sleep(50)
    }
    return null
}

/**
 * Pass priority until the game reaches Main1.
 *
 * Uses the pending action's phase (set when the engine blocks) instead of
 * polling `game.phaseHandler.phase` -- eliminates a race where the live phase
 * is checked before the pending is found, causing an accidental pass at Main1.
 */
/**
 * Wait for a pending interactive prompt (targeting, choices, etc.).
 * Returns null on timeout.
 */
fun awaitPrompt(
    b: GameBridge,
    timeoutMs: Long = 5_000,
): InteractivePromptBridge.PendingPrompt? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val p = b.promptBridge.getPendingPrompt()
        if (p != null && !p.future.isDone) return p
        Thread.sleep(50)
    }
    return null
}

fun advanceToMain1(b: GameBridge, maxPasses: Int = 20) {
    val game = b.getGame()!!
    var passes = 0
    var lastId: String? = null
    while (passes < maxPasses) {
        val pending = awaitFreshPending(b, lastId)
            ?: error("Timed out waiting for priority while advancing to Main1 (phase=${game.phaseHandler.phase})")
        if (pending.state.phase == "MAIN1") return
        b.actionBridge.submitAction(pending.actionId, PlayerAction.PassPriority)
        lastId = pending.actionId
        passes++
    }
}
