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
 * Production code passes pre-built GSMs to [GameBridge.snapshotDiffBaseline] directly;
 * this wrapper avoids boilerplate in tests.
 */
fun GameBridge.snapshotFromGame(game: Game, gameStateId: Int = 0) {
    snapshotDiffBaseline(StateMapper.buildFromGame(game, gameStateId, "", this).gsm)
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
        val p = b.actionBridge(1).getPending()
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
        val p = b.promptBridge(1).getPendingPrompt()
        if (p != null && !p.future.isDone) return p
        Thread.sleep(50)
    }
    return null
}

/**
 * Advance the engine to a phase matching [predicate] by submitting one
 * PassPriority at a time via the bridge. No AutoPassEngine involvement —
 * each pass is a single engine step, so there is no phase overshoot.
 *
 * Returns the [GameActionBridge.PendingAction] at the target phase
 * (the engine is blocked, so game state is stable for assertions).
 *
 * Combat phases are safe: submitting PassPriority during declareAttackers
 * / declareBlockers means "no attackers / no blockers" — the engine
 * continues to the next phase without hanging.
 */
fun advanceTo(
    b: GameBridge,
    maxPasses: Int = 50,
    timeoutMs: Long = 15_000,
    predicate: (phase: String, turn: Int) -> Boolean,
): GameActionBridge.PendingAction {
    val game = b.getGame()!!
    var lastId: String? = null
    repeat(maxPasses) {
        val pending = awaitFreshPending(b, lastId, timeoutMs)
            ?: error("Timed out waiting for priority (phase=${game.phaseHandler.phase}, turn=${game.phaseHandler.turn})")
        if (predicate(pending.state.phase, pending.state.turn)) return pending
        b.actionBridge(1).submitAction(pending.actionId, PlayerAction.PassPriority)
        lastId = pending.actionId
    }
    error("Max passes ($maxPasses) exceeded advancing to target phase (current: ${game.phaseHandler.phase}, turn ${game.phaseHandler.turn})")
}

/** Advance to a specific [phase], optionally on a specific [turn]. */
fun advanceToPhase(
    b: GameBridge,
    phase: String,
    turn: Int? = null,
    maxPasses: Int = 50,
) = advanceTo(b, maxPasses) { p, t -> p == phase && (turn == null || t == turn) }

/** Advance to COMBAT_DECLARE_ATTACKERS. */
fun advanceToCombat(b: GameBridge, turn: Int? = null) =
    advanceToPhase(b, "COMBAT_DECLARE_ATTACKERS", turn)

/** Advance to MAIN2. */
fun advanceToMain2(b: GameBridge, turn: Int? = null) =
    advanceToPhase(b, "MAIN2", turn)

fun advanceToMain1(b: GameBridge, maxPasses: Int = 20) {
    advanceToPhase(b, "MAIN1", maxPasses = maxPasses)
}
