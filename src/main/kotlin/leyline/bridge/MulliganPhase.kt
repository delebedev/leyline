package leyline.bridge

/**
 * Mulligan bridge lifecycle phases.
 *
 * Tracks what the engine is waiting for during the mulligan sequence.
 * Used by [MulliganBridge] and read by callers polling for mulligan state.
 */
enum class MulliganPhase {
    /** Engine dealt a hand and is blocking for keep/mulligan decision. */
    WaitingKeep,

    /** Player kept with London mulligan; engine is blocking for bottom-card selection. */
    WaitingTuck,
}
