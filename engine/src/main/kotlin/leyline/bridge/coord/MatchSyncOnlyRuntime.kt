package leyline.bridge.coord

import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.SynchronizationPresentation
import leyline.bridge.types.SeatId
import leyline.game.bundle.LogicalSequencePlanner

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
        synchronized(owner.bridge.projectionBuildLock) {
            synchronized(owner.feedLock) {
                owner.ensureOpen()
                val prior = owner.bridge.projectionStateSnapshot()
                val planner = LogicalSequencePlanner(prior.sequence)
                val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                val feed = owner.feed(seatId)
                val routes = owner.viewerRoutes()
                val prepared =
                    try {
                        beforeMaterialization?.invoke()
                        feed.builder
                            .prepareStateOnlyDiff(
                                game,
                                planner,
                                routes,
                                phaseTransition =
                                    pending.state.synchronizationPresentation == SynchronizationPresentation.PhaseTransition,
                            )
                    } catch (ex: Exception) {
                        owner.fail(ex)
                    }
                val outputs =
                    prepared.viewers.map { output -> PreparedViewerOutput(output.seatId, output.batches) }
                val messages = outputs.single { it.seatId == seatId }.batches.single()
                owner.cutInstaller.install(
                    PreparedCut.prepareForViewers(
                        prior,
                        planner,
                        outputs,
                        prepared.transition,
                        prepared.closesPlaybackFrame,
                        playbackOwnerSeatId = seatId,
                    ),
                    CutInstallHooks(beforeEnqueue = beforeEnqueue, beforeInstall = beforeInstall),
                ) { ex -> owner.fail(ex) }
                feed.requestedCut = null
                owner.actions.markSynchronizationPublished(seatId, pending.actionId, messages)
            }
        }
    }
}
