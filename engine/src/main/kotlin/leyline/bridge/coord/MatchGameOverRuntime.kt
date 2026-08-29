package leyline.bridge.coord

import forge.game.player.GameLossReason
import leyline.bridge.types.SeatId
import leyline.bridge.types.opponent
import leyline.game.annotations.AnnotationLossReason
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.ResultReason
import wotc.mtgo.gre.external.messaging.Messages.ResultType

/** Immutable terminal semantics committed with the game-over cut. */
internal data class GameOverOutcome(
    val result: ResultType,
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
    private var committedOutcome: GameOverOutcome? = null

    fun committed(): GameOverOutcome? = synchronized(owner.feedLock) { committedOutcome }

    /** Materialize terminal semantics while the engine still owns the live Forge graph. */
    fun publishFromEngine(seatId: SeatId) {
        val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
        check(game.isGameOver) { "Engine terminal outcome requires a completed game" }
        val players =
            owner.bridge
                .gameSeatIds()
                .sorted()
                .map { SeatId(it) to owner.bridge.getPlayer(SeatId(it)) }
        val winningSeat = players.firstOrNull { (_, player) -> player?.getOutcome()?.hasWon() == true }?.first
        val losingPlayer = players.firstOrNull { (seat, _) -> seat != winningSeat && winningSeat != null }
        publish(
            seatId,
            GameOverOutcome(
                result = if (winningSeat == null) ResultType.Draw_a544 else ResultType.WinLoss,
                winningTeam = winningSeat?.value ?: 0,
                reason = ResultReason.Game_ae0a,
                losingPlayerSeatId = losingPlayer?.first?.value ?: 0,
                lossReason = annotationLossReasonFor(losingPlayer?.second?.getOutcome()?.lossState),
            ),
        )
    }

    /** Concession semantics require no live Forge observation. */
    fun publishConcession(seatId: SeatId) =
        publish(
            seatId,
            GameOverOutcome(
                result = ResultType.WinLoss,
                winningTeam = seatId.opponent.value,
                reason = ResultReason.Concede,
                losingPlayerSeatId = seatId.value,
                lossReason = AnnotationLossReason.Concede,
            ),
        )

    /** Publish pending resolution facts before the terminal game-over sequence. */
    fun publish(
        seatId: SeatId,
        outcome: GameOverOutcome,
    ) {
        owner.beforePublicationLock?.invoke()
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            committedOutcome?.let { committed ->
                if (committed != outcome) {
                    owner.fail(IllegalStateException("Terminal outcome is already committed"))
                }
                return
            }
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
                        result = outcome.result,
                        winningTeam = outcome.winningTeam,
                        counter = planner,
                        routes = routes,
                        reason = outcome.reason,
                        losingPlayerSeatId = outcome.losingPlayerSeatId,
                        lossReason = outcome.lossReason,
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
                onInstalled = {
                    committedOutcome = outcome
                    owner.feed(seatId).requestedCut = null
                },
            ) { ex -> owner.fail(ex) }
        }
    }

    fun reset() {
        check(Thread.holdsLock(owner.feedLock)) { "Game-over reset requires coordinator feed ownership" }
        committedOutcome = null
    }
}

private fun annotationLossReasonFor(lossState: GameLossReason?): AnnotationLossReason =
    when (lossState) {
        GameLossReason.Poisoned -> AnnotationLossReason.Poison
        GameLossReason.Milled -> AnnotationLossReason.DrawFromEmptyLibrary
        GameLossReason.Conceded -> AnnotationLossReason.Concede
        GameLossReason.LifeReachedZero,
        GameLossReason.CommanderDamage,
        GameLossReason.IntentionalDraw,
        GameLossReason.OpponentWon,
        GameLossReason.SpellEffect,
        null,
        -> AnnotationLossReason.LifeTotal
    }
