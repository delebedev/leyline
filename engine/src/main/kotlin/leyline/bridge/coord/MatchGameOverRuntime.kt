package leyline.bridge.coord

import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationLossReason
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.ResultReason

/** Immutable lifecycle inputs retained by the session at the game-over seam. */
internal data class GameOverIntent(
    val winningTeam: Int,
    val reason: ResultReason,
    val losingPlayerSeatId: Int,
    val lossReason: AnnotationLossReason,
)

/** Owns materialization and publication of the terminal lifecycle cut. */
internal class MatchGameOverRuntime(
    private val owner: MatchCutCoordinator,
) {
    internal var beforeMaterialization: (() -> Unit)? = null
    internal var beforeInstall: (() -> Unit)? = null

    /** Publish pending resolution facts before the terminal game-over sequence. */
    fun publish(
        seatId: SeatId,
        intent: GameOverIntent,
    ) {
        owner.beforePublicationLock?.invoke()
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
            owner.requireViewer(seatId)
            val routes = owner.viewerRoutes()
            val initialProjection = owner.bridge.projectionStateSnapshot()
            val planner = LogicalSequencePlanner(initialProjection.sequence)
            val pending =
                try {
                    beforeMaterialization?.invoke()
                    if (owner.bridge.hasPendingEvents()) {
                        owner.feed(seatId).builder.prepareStateOnlyDiff(game, planner, routes)
                    } else {
                        null
                    }
                } catch (ex: Exception) {
                    owner.fail(ex)
                }
            val priorProjection = pending?.transition?.nextState ?: initialProjection
            val terminal =
                try {
                    owner.feed(seatId).builder.prepareGameOverBundle(
                        winningTeam = intent.winningTeam,
                        counter = planner,
                        routes = routes,
                        reason = intent.reason,
                        losingPlayerSeatId = intent.losingPlayerSeatId,
                        lossReason = intent.lossReason,
                        priorProjection = priorProjection,
                    )
                } catch (ex: Exception) {
                    owner.fail(ex)
                }

            val outputs =
                routes.map { route ->
                    val viewer = route.viewer
                    val pendingMessages =
                        pending
                            ?.viewers
                            ?.single { it.seatId == viewer.seatId }
                            ?.batches
                            ?.flatten()
                            .orEmpty()
                    val terminalMessages =
                        terminal.viewers
                            .single { it.seatId == viewer.seatId }
                            .batches
                            .flatten()
                    PreparedViewerOutput(viewer.seatId, listOf(pendingMessages + terminalMessages))
                }
            val transition =
                ProjectionTransition(
                    expectedRevision = initialProjection.revision,
                    nextState = terminal.transition.nextState.copy(revision = initialProjection.revision + 1),
                    acknowledgements = pending?.transition?.acknowledgements ?: terminal.transition.acknowledgements,
                )
            owner.cutInstaller.install(
                cut =
                    PreparedCut.prepareForViewers(
                        initialProjection,
                        planner,
                        outputs,
                        transition,
                        closesPlaybackFrame = pending != null,
                        playbackOwnerSeatId = seatId.takeIf { pending != null },
                    ),
                hooks = CutInstallHooks(beforeInstall = beforeInstall),
                onInstalled = { owner.feed(seatId).requestedCut = null },
            ) { ex -> owner.fail(ex) }
        }
    }
}
