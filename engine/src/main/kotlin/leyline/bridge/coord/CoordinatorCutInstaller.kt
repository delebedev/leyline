package leyline.bridge.coord

import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Prepared output and projection transition for one single-batch coordinator cut.
 *
 * A null [transition] means the cut publishes output without advancing projection
 * state. Such a cut counts as installed the moment its batch is enqueued, so only
 * a failure before that point withdraws the batch.
 */
internal data class PreparedCut(
    val messages: List<GREToClientMessage>,
    val transition: ProjectionTransition?,
    val closesPlaybackFrame: Boolean,
)

/** Family-owned observation points around one installation. Test seams only. */
internal data class CutInstallHooks(
    val beforeEnqueue: (() -> Unit)? = null,
    val beforeInstall: (() -> Unit)? = null,
    val afterInstall: (() -> Unit)? = null,
)

/**
 * Sole implementation of the single-batch coordinator cut transaction.
 *
 * Runs while the caller already holds the counter, projection-build, and feed
 * locks. It owns enqueue ordering, projection commit, rollback of its own batch
 * before install, playback acknowledgement, and typed failure handoff.
 *
 * It allocates no protocol messages, signals no priority, awaits no response,
 * and understands no prompt or action semantics. Callers keep their window
 * state, materializers, response validation, timeout policy, and side effects.
 */
internal class CoordinatorCutInstaller(
    private val owner: MatchCutCoordinator,
) {
    /**
     * Installs [cut] into [feed], then runs [onInstalled] inside the same
     * transaction once the cut is fully published.
     *
     * [replaces], when nonempty, is retired after the new batch is enqueued and
     * restored if projection installation fails. A failure before the projection
     * transition installs removes only this batch; competing state and unrelated queued output are untouched. When the
     * transition did not install, [onRollback] runs after the batch is withdrawn
     * so the caller can undo side effects it owns alongside the cut. Any failure
     * is handed to [onFailure] so the family can attach its exact cut.
     */
    fun install(
        feed: MatchCutCoordinator.ViewerFeed,
        cut: PreparedCut,
        hooks: CutInstallHooks = CutInstallHooks(),
        replaces: List<GREToClientMessage> = emptyList(),
        onInstalled: (() -> Unit)? = null,
        onRollback: (() -> Unit)? = null,
        onFailure: (Throwable) -> Nothing,
    ) {
        val batch = cut.messages
        var enqueued = false
        var replaced = false
        var installed = false
        try {
            hooks.beforeEnqueue?.invoke()
            feed.beforeBatchEnqueue?.invoke(0, batch)
            feed.queue.add(batch)
            enqueued = true
            if (replaces.isNotEmpty()) {
                check(owner.removeOwnedBatch(feed, replaces)) { "Replaced coordinator batch is already visible" }
                replaced = true
            }
            hooks.beforeInstall?.invoke()
            cut.transition?.let { transition ->
                owner.bridge.commitProjection(transition) { installed = true }
            } ?: run { installed = true }
            hooks.afterInstall?.invoke()
            if (cut.closesPlaybackFrame) owner.bridge.acknowledgePlaybackFrame(feed.seatId)
            onInstalled?.invoke()
            owner.signalDelivery()
        } catch (ex: Exception) {
            if (!installed) {
                if (enqueued) owner.removeOwnedBatch(feed, batch)
                if (replaced) feed.queue.add(replaces)
                onRollback?.invoke()
            }
            onFailure(ex)
        }
    }
}
