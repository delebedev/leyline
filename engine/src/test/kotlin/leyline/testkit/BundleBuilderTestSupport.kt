package leyline.testkit

import forge.game.Game
import leyline.bridge.PriorityActionCandidates
import leyline.bridge.types.SeatId
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.game.state.ProjectionTransition
import leyline.game.state.ProjectionViewer
import leyline.game.state.ProjectionViewerRole
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.DeclareAttackersReq

/** Test-only adapters for isolated materializer checks that intentionally install their prepared transition. */
internal object BundleBuilderTestSupport {
    fun postAction(
        builder: BundleBuilder,
        bridge: GameBridge,
        game: Game,
        counter: LogicalSequencePlanner,
        priorityCandidates: PriorityActionCandidates? = null,
    ): BundleBuilder.BundleResult = installAction(bridge, builder.preparePostAction(game, counter, priorityCandidates = priorityCandidates))

    fun phaseTransition(
        builder: BundleBuilder,
        bridge: GameBridge,
        game: Game,
        counter: LogicalSequencePlanner,
    ): BundleBuilder.BundleResult = installAction(bridge, builder.preparePhaseTransitionDiff(game, counter))

    fun declareAttackers(
        builder: BundleBuilder,
        bridge: GameBridge,
        game: Game,
        counter: LogicalSequencePlanner,
        request: DeclareAttackersReq? = null,
    ): BundleBuilder.BundleResult = installAction(bridge, builder.prepareDeclareAttackers(game, counter, request))

    fun declareBlockers(
        builder: BundleBuilder,
        bridge: GameBridge,
        game: Game,
        counter: LogicalSequencePlanner,
    ): BundleBuilder.BundleResult = installAction(bridge, builder.prepareDeclareBlockers(game, counter))

    fun stateOnly(
        builder: BundleBuilder,
        bridge: GameBridge,
        game: Game,
        counter: LogicalSequencePlanner,
    ): BundleBuilder.BundleResult {
        val prepared =
            builder.prepareStateOnlyDiff(
                game,
                counter,
                listOf(
                    BundleBuilder.ViewerRoute(
                        ProjectionViewer(SeatId(Board.SEAT_ID), ProjectionViewerRole.Player),
                        builder,
                    ),
                ),
            )
        bridge.commitProjection(prepared.transition)
        if (prepared.closesPlaybackFrame) bridge.acknowledgePlaybackFrame(SeatId(Board.SEAT_ID))
        return BundleBuilder.BundleResult(prepared.batches.single())
    }

    fun buildActions(
        bridge: GameBridge,
        priorityCandidates: PriorityActionCandidates? = null,
    ): ActionsAvailableReq {
        val game = bridge.getGame() ?: return ActionMapper.passOnlyActions()
        val prior = bridge.projectionStateSnapshot()
        val (actions, next) =
            bridge.editProjection(prior) {
                val snapshot = GsmSnapshot.capture(game, bridge, Board.TEST_MATCH_ID, 0)
                ActionMapper.buildProjectionFromSnapshot(Board.SEAT_ID, snapshot, bridge, priorityCandidates).actions
            }
        bridge.commitProjection(ProjectionTransition(prior.revision, next))
        return actions
    }

    private fun installAction(
        bridge: GameBridge,
        prepared: BundleBuilder.ActionWindowPrepared,
    ): BundleBuilder.BundleResult {
        prepared.transition?.let(bridge::commitProjection)
        if (prepared.closesPlaybackFrame) bridge.acknowledgePlaybackFrame(SeatId(Board.SEAT_ID))
        return prepared.bundle
    }
}
