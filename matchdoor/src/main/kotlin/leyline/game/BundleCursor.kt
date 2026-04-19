package leyline.game

import leyline.game.snapshot.GsmSnapshot

/**
 * Bundle-sequence cursor: the snapshot that was last sent to the client.
 * Serves as the `prev` baseline for the next `StateMapper.buildDiff` call
 * in the bundle loop.
 *
 * Currently hosted on [GameBridge.bundleCursor] for backward compatibility
 * with existing callers. The lift of ownership to the session layer
 * (MatchSession) is tracked as a follow-up — design questions around
 * rejoin/restore and TargetingHandler's protocol-level reset semantic need
 * resolution before a clean lift.
 *
 * Keeping the cursor as a typed value (rather than a raw `var` on bridge)
 * makes the future lift mechanical: reassign ownership, no callsite churn.
 */
class BundleCursor {
    var lastSent: GsmSnapshot? = null
}
