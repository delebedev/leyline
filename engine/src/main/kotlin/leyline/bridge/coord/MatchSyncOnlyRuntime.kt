package leyline.bridge.coord

import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.SynchronizationPresentation
import leyline.bridge.types.SeatId
import leyline.game.bundle.LogicalSequencePlanner
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

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
                        val phaseMessages =
                            if (pending.state.synchronizationPresentation == SynchronizationPresentation.PhaseTransition) {
                                feed.builder
                                    .preparePhaseTransitionDiff(
                                        game,
                                        planner,
                                        priorityActions = ActionsAvailableReq.getDefaultInstance(),
                                        includePriorityPrompt = false,
                                    ).bundle.messages
                            } else {
                                emptyList()
                            }
                        feed.builder
                            .prepareStateOnlyDiff(game, planner, routes)
                            .let { it to phaseMessages }
                    } catch (ex: Exception) {
                        owner.fail(ex)
                    }
                val (state, phaseMessages) = prepared
                val outputs =
                    state.viewers.map { output ->
                        val messages = output.batches.flatten()
                        PreparedViewerOutput(
                            output.seatId,
                            listOf(
                                if (output.seatId == seatId && phaseMessages.isNotEmpty()) {
                                    phaseMessages + coalescePhaseAnnotations(messages)
                                } else {
                                    messages
                                },
                            ),
                        )
                    }
                val messages = outputs.single { it.seatId == seatId }.batches.single()
                owner.cutInstaller.install(
                    PreparedCut.prepareForViewers(
                        prior,
                        planner,
                        outputs,
                        state.transition,
                        state.closesPlaybackFrame,
                        playbackOwnerSeatId = seatId,
                    ),
                    CutInstallHooks(beforeEnqueue = beforeEnqueue, beforeInstall = beforeInstall),
                ) { ex -> owner.fail(ex) }
                feed.requestedCut = null
                owner.actions.markSynchronizationPublished(seatId, pending.actionId, messages)
            }
        }
    }

    private fun coalescePhaseAnnotations(messages: List<GREToClientMessage>): List<GREToClientMessage> =
        messages.map { message ->
            if (!message.hasGameStateMessage()) return@map message
            val annotations = message.gameStateMessage.annotationsList
            val phases = annotations.filter { AnnotationType.PhaseOrStepModified in it.typeList }
            if (phases.size <= 1) return@map message
            val state =
                message.gameStateMessage
                    .toBuilder()
                    .clearAnnotations()
                    .addAnnotations(phases.last())
                    .addAllAnnotations(annotations.filterNot { AnnotationType.PhaseOrStepModified in it.typeList })
            message.toBuilder().setGameStateMessage(state).build()
        }
}
