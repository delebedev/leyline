package leyline.game.bundle

import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.snapshot.GsmSnapshot

/**
 * Bundle-sequence cursor: the snapshot most recently committed during bundle
 * construction. Serves as the `prev` baseline for the next
 * `StateMapper.buildDiff` call in the bundle loop. Construction advances this
 * value before the sink accepts the resulting messages, so it is a projection
 * baseline rather than delivery acknowledgement.
 *
 * One instance per [leyline.game.state.GameBridge], shared by owner-side
 * [BundleBuilder] instances. Keeping a single owner-committed cursor ensures
 * diff baselines agree across interactive and spectator reductions.
 */
class BundleCursor {
    @Volatile var lastSent: GsmSnapshot? = null

    private var pendingPSuT: PSuTPending? = null

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

    /**
     * Queue a PlayerSubmittedTargets annotation to be appended to the next
     * outgoing GSM. Set by [leyline.match.TargetingHandler.onSubmitTargets]
     * once the engine has accepted the chosen targets; consumed by the next
     * [BundleBuilder] method that builds a diff. One-shot — only the first
     * subsequent bundle picks it up.
     */
    @Synchronized
    fun queuePSuT(
        spellInstanceId: InstanceId,
        casterSeatId: SeatId,
    ) {
        pendingPSuT = PSuTPending(spellInstanceId, casterSeatId)
    }

    /** Read the queued PSuT without consuming it during frame assembly. */
    @Synchronized
    fun pendingPSuT(): PSuTPending? = pendingPSuT

    /** Consume the PSuT only after the frame carrying it has committed. */
    @Synchronized
    fun consumePSuT(expected: PSuTPending) {
        check(pendingPSuT == expected) { "Pending PlayerSubmittedTargets changed during frame assembly" }
        pendingPSuT = null
    }

    data class PSuTPending(
        val spellInstanceId: InstanceId,
        val casterSeatId: SeatId,
    )
}
