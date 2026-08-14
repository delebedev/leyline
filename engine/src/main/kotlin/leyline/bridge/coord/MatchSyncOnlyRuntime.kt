package leyline.bridge.coord

import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.types.SeatId

/** State-only pre-block publication for a pass-only synchronization stop. */
internal class MatchSyncOnlyRuntime(
    private val owner: MatchCutCoordinator,
) {
    internal var beforeMaterialization: (() -> Unit)? = null
    internal var beforeEnqueue: (() -> Unit)? = null
    internal var beforeInstall: (() -> Unit)? = null

    fun publish(
        seatId: SeatId,
        pending: GameActionBridge.PendingAction,
    ) {
        var requestAutoAdvance = false
        owner.beforePublicationLock?.invoke()
        synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                    val feed = owner.feed(seatId)
                    val prepared =
                        try {
                            beforeMaterialization?.invoke()
                            feed.builder.prepareStateOnlyDiff(game, owner.counter)
                        } catch (ex: Exception) {
                            owner.fail(ex)
                        }
                    val messages = prepared.bundle.messages
                    var enqueued = false
                    var installed = false
                    try {
                        beforeEnqueue?.invoke()
                        feed.queue.add(messages)
                        enqueued = true
                        beforeInstall?.invoke()
                        prepared.transition?.let { transition ->
                            owner.bridge.commitProjection(transition) { installed = true }
                        } ?: run {
                            installed = true
                        }
                        if (prepared.closesPlaybackFrame) owner.bridge.acknowledgePlaybackFrame(seatId)
                    } catch (ex: Exception) {
                        if (enqueued && !installed) owner.removeOwnedBatch(feed, messages)
                        owner.fail(ex)
                    }
                    feed.requestedCut = null
                    pending.published = true
                    requestAutoAdvance = owner.bridge.consumePromptTimeoutNeedsAutoAdvance()
                }
            }
        }
        if (requestAutoAdvance) {
            try {
                owner.bridge.autoAdvanceRequester?.invoke("prompt timeout synchronization queued")
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        }
    }
}
