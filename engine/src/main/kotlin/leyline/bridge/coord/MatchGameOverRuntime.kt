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
        synchronized(owner.bridge.projectionBuildLock) {
            synchronized(owner.feedLock) {
                owner.ensureOpen()
                val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                owner.registerViewer(seatId)
                val feed = owner.feed(seatId)
                val initialProjection = owner.bridge.projectionStateSnapshot()
                val planner = LogicalSequencePlanner(initialProjection.sequence)
                val pending =
                    try {
                        beforeMaterialization?.invoke()
                        if (owner.bridge.hasPendingEvents()) {
                            feed.builder.prepareStateOnlyDiff(game, planner)
                        } else {
                            null
                        }
                    } catch (ex: Exception) {
                        owner.fail(ex)
                    }
                val priorProjection = pending?.transition?.nextState ?: owner.bridge.projectionStateSnapshot()
                val terminal =
                    try {
                        feed.builder.prepareGameOverBundle(
                            winningTeam = intent.winningTeam,
                            counter = planner,
                            reason = intent.reason,
                            losingPlayerSeatId = intent.losingPlayerSeatId,
                            lossReason = intent.lossReason,
                            priorProjection = priorProjection,
                        )
                    } catch (ex: Exception) {
                        owner.fail(ex)
                    }

                val messages = (pending?.bundle?.messages.orEmpty() + terminal.bundle.messages)
                val terminalTransition = checkNotNull(terminal.transition)
                val transition =
                    ProjectionTransition(
                        expectedRevision = initialProjection.revision,
                        nextState = terminalTransition.nextState.copy(revision = initialProjection.revision + 1),
                        acknowledgements = pending?.transition?.acknowledgements ?: terminalTransition.acknowledgements,
                    )
                owner.cutInstaller.install(
                    feed = feed,
                    cut =
                        PreparedCut.prepare(
                            initialProjection,
                            planner,
                            messages,
                            transition,
                            closesPlaybackFrame = pending?.closesPlaybackFrame == true,
                        ),
                    hooks = CutInstallHooks(beforeInstall = beforeInstall),
                    onInstalled = { feed.requestedCut = null },
                ) { ex -> owner.fail(ex) }
            }
        }
    }
}
