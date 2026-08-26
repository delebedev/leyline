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
                    owner.cutInstaller.install(
                        feed,
                        PreparedCut(prepared.bundle.messages, prepared.transition, prepared.closesPlaybackFrame),
                        CutInstallHooks(beforeEnqueue = beforeEnqueue, beforeInstall = beforeInstall),
                    ) { ex -> owner.fail(ex) }
                    feed.requestedCut = null
                    owner.actions.markSynchronizationPublished(seatId, pending.actionId)
                }
            }
        }
    }
}
