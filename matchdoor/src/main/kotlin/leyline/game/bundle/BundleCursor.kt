package leyline.game.bundle

import leyline.game.snapshot.GsmSnapshot

/**
 * Bundle-sequence cursor: the snapshot that was last sent to the client.
 * Serves as the `prev` baseline for the next `StateMapper.buildDiff` call
 * in the bundle loop.
 *
 * One instance per [leyline.game.state.GameBridge], shared by every [BundleBuilder] bound to
 * that bridge — both the session-layer builder and the engine-thread
 * [leyline.game.GamePlayback] builder. Keeping a single cursor ensures diff baselines
 * agree across the two construction sites.
 *
 * Lifting ownership fully to the session layer is a future design — it
 * needs rejoin/restore semantics and a channel from MatchSession to
 * GamePlayback. Typing the cursor today makes that lift mechanical.
 */
class BundleCursor {
    var lastSent: GsmSnapshot? = null

    /**
     * Drop the diff baseline so the next bundle rebuilds as a Full GSM.
     *
     * Used when state transitions invalidate the diff invariant — e.g.
     * library-search reveal cleanup: the previous baseline includes revealed
     * library objects that must disappear on the next bundle, but a diff
     * against the stale baseline won't emit deletes for them. Protocol handles
     * this via Shuffle (OldIds→NewIds), not yet implemented (#42); this
     * invalidation is the workaround.
     */
    fun invalidate() {
        lastSent = null
    }
}
