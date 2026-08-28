package leyline.bridge.coord

import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.SeatId
import leyline.game.bundle.LifecycleMessageMaterializer
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.Prompt

/** Owns preparation and ordered publication of match lifecycle output. */
internal class MatchLifecycleRuntime(
    private val owner: MatchCutCoordinator,
) {
    private data class InitialPublication(
        val gameStateId: Int,
        val outputOrdinal: Long,
        val outputs: List<PreparedViewerOutput>,
    )

    private var initialPublication: InitialPublication? = null
    private var familiarStartupClaimed = false

    data class PuzzleReplacementPublication(
        val gameStateId: Int,
        val objectCount: Int,
        val zoneCount: Int,
    )

    data class PuzzleInitialPublication(
        val gameStateId: Int,
        val kind: PendingActionKind,
        val deliveryBoundaryMsgId: Int,
    )

    data class FullStatePublication(
        val gameStateId: Int,
        val objectCount: Int,
        val zoneCount: Int,
    )

    private data class PreparedPuzzleInitial(
        val kind: PendingActionKind,
        val messages: LifecycleMessageMaterializer.LifecycleMessages,
        val replaces: List<GREToClientMessage>? = null,
    )

    private data class PreparedPuzzleReplacement(
        val messages: LifecycleMessageMaterializer.LifecycleMessages,
        val replaces: List<GREToClientMessage>? = null,
        val objectCount: Int,
        val zoneCount: Int,
    )

    fun publishInitial(
        seatId: SeatId,
        includeStartingPlayerPrompt: Boolean,
    ): Int {
        owner.requireViewer(seatId)
        return synchronized(owner.bridge.projectionBuildLock) {
            synchronized(owner.feedLock) {
                initialPublication?.let { publication ->
                    val viewerIndex = publication.outputs.indexOfFirst { it.seatId == seatId }
                    check(viewerIndex >= 0) { "Initial output is unavailable for viewer $seatId" }
                    val output = publication.outputs[viewerIndex]
                    val feed = owner.feed(seatId)
                    output.batches.forEachIndexed { batchIndex, messages ->
                        val present = feed.queue.any { it.ordinal == publication.outputOrdinal && it.batchIndex == batchIndex }
                        if (!present) {
                            feed.queue.addFirst(
                                CommittedOutputBatch(
                                    ordinal = publication.outputOrdinal,
                                    batchIndex = batchIndex,
                                    messages = messages,
                                    viewerIndex = viewerIndex,
                                ),
                            )
                        }
                    }
                    owner.signalDelivery()
                    return@synchronized publication.gameStateId
                }
                owner.ensureOpen()
                val prior = owner.bridge.projectionStateSnapshot()
                val planner = LogicalSequencePlanner(prior.sequence)
                val gameStateId = planner.nextGsId()
                val prepared =
                    prepare {
                        LifecycleMessageMaterializer.initialBundles(
                            viewers = owner.registeredViewers(),
                            matchId = owner.matchId,
                            gameStateId = gameStateId,
                            planner = planner,
                            bridge = owner.bridge,
                            dieRollWinner = owner.bridge.dieRollWinner,
                            includeStartingPlayerPrompt = includeStartingPlayerPrompt,
                        )
                    }
                val cut =
                    PreparedCut.prepareForViewers(
                        prior,
                        planner,
                        prepared.viewers.map { (viewerSeat, messages) ->
                            PreparedViewerOutput(viewerSeat, listOf(messages))
                        },
                        prepared.transition,
                        closesPlaybackFrame = false,
                    )
                owner.cutInstaller.install(
                    cut,
                    onInstalled = {
                        initialPublication = InitialPublication(gameStateId, cut.outputOrdinal, cut.viewerOutputs)
                    },
                    onFailure = owner::fail,
                )
                gameStateId
            }
        }
    }

    /** Claim the automatic Familiar startup transition once both match seats are connected. */
    fun claimFamiliarStartup(): Boolean =
        synchronized(owner.bridge.projectionBuildLock) {
            synchronized(owner.feedLock) {
                if (familiarStartupClaimed) {
                    false
                } else {
                    familiarStartupClaimed = true
                    true
                }
            }
        }

    fun publishDealHand(
        seatId: SeatId,
        deletedInstanceIds: List<Int> = emptyList(),
    ): Int =
        withPlan(seatId) { prior, planner, gameStateId ->
            val prepared =
                prepare {
                    LifecycleMessageMaterializer.dealHand(
                        planner.currentMsgId(),
                        gameStateId,
                        owner.bridge,
                        seatId,
                        deletedInstanceIds,
                    )
                }
            install(seatId, prior, planner, prepared)
            gameStateId
        }

    fun publishDealHandMulligan(seatId: SeatId): Int =
        withPlan(seatId) { prior, planner, gameStateId ->
            val prepared =
                prepare {
                    LifecycleMessageMaterializer.dealHandMulliganSeat2(
                        planner.currentMsgId(),
                        gameStateId,
                        owner.bridge,
                    )
                }
            install(seatId, prior, planner, prepared)
            gameStateId
        }

    fun publishMulliganRequest(
        seatId: SeatId,
        mulliganCount: Int,
        numCards: Int,
    ): Int =
        withPlan(seatId) { prior, planner, gameStateId ->
            val prepared =
                prepare {
                    LifecycleMessageMaterializer.mulliganReqSeat1(
                        planner.currentMsgId(),
                        gameStateId,
                        owner.bridge,
                        mulliganCount,
                        numCards,
                    )
                }
            install(seatId, prior, planner, prepared)
            gameStateId
        }

    fun publishPuzzleInitial(
        seatId: SeatId,
        actionId: String,
    ): PuzzleInitialPublication =
        withPlan(seatId) { prior, planner, gameStateId ->
            val prepared = prepare { preparePuzzleInitial(seatId, actionId, gameStateId, planner) }
            install(
                seatId,
                prior,
                planner,
                prepared.messages,
                prepared.replaces.orEmpty(),
                actionId.takeIf { prepared.replaces != null },
            )
            PuzzleInitialPublication(
                gameStateId = gameStateId,
                kind = prepared.kind,
                deliveryBoundaryMsgId = prepared.messages.nextMsgId,
            )
        }

    fun publishPuzzleReplacement(
        seatId: SeatId,
        deletedInstanceIds: List<Int>,
        actionId: String,
    ): PuzzleReplacementPublication =
        withPlan(seatId) { prior, planner, gameStateId ->
            val prepared = prepare { preparePuzzleReplacement(seatId, deletedInstanceIds, actionId, gameStateId, planner) }
            install(
                seatId,
                prior,
                planner,
                prepared.messages,
                prepared.replaces.orEmpty(),
                actionId.takeIf { prepared.replaces != null },
            )
            PuzzleReplacementPublication(
                gameStateId = gameStateId,
                objectCount = prepared.objectCount,
                zoneCount = prepared.zoneCount,
            )
        }

    fun publishFullState(seatId: SeatId): FullStatePublication =
        withPlan(seatId) { prior, planner, gameStateId ->
            val full = owner.feed(seatId).builder.prepareFullState(checkNotNull(owner.bridge.getGame()), gameStateId)
            val messages =
                listOf(
                    GREToClientMessage
                        .newBuilder()
                        .setType(GREMessageType.GameStateMessage_695e)
                        .setMsgId(planner.nextMsgId())
                        .setGameStateId(gameStateId)
                        .addSystemSeatIds(seatId.value)
                        .setGameStateMessage(full.result.gsm)
                        .build(),
                    GREToClientMessage
                        .newBuilder()
                        .setType(GREMessageType.ActionsAvailableReq_695e)
                        .setMsgId(planner.nextMsgId())
                        .setGameStateId(gameStateId)
                        .addSystemSeatIds(seatId.value)
                        .setActionsAvailableReq(full.result.actions)
                        .setPrompt(Prompt.newBuilder().setPromptId(leyline.game.mapping.PromptIds.PASS_PRIORITY).build())
                        .build(),
                )
            owner.cutInstaller.install(
                owner.feed(seatId),
                PreparedCut.prepare(prior, planner, messages, full.transition, closesPlaybackFrame = false),
                onFailure = owner::fail,
            )
            FullStatePublication(gameStateId, full.result.gsm.gameObjectsCount, full.result.gsm.zonesCount)
        }

    private inline fun <T> withPlan(
        seatId: SeatId,
        block: (ProjectionState, LogicalSequencePlanner, Int) -> T,
    ): T {
        owner.registerViewer(seatId)
        return synchronized(owner.bridge.projectionBuildLock) {
            synchronized(owner.feedLock) {
                owner.ensureOpen()
                val prior = owner.bridge.projectionStateSnapshot()
                val planner = LogicalSequencePlanner(prior.sequence)
                block(prior, planner, planner.nextGsId())
            }
        }
    }

    private fun install(
        seatId: SeatId,
        prior: ProjectionState,
        planner: LogicalSequencePlanner,
        prepared: LifecycleMessageMaterializer.LifecycleMessages,
        replaces: List<GREToClientMessage> = emptyList(),
        synchronizationActionId: String? = null,
    ) {
        planner.setMsgId(prepared.nextMsgId)
        owner.cutInstaller.install(
            feed = owner.feed(seatId),
            cut = PreparedCut.prepare(prior, planner, prepared.messages, prepared.transition, closesPlaybackFrame = false),
            replaces = replaces,
            onInstalled = {
                synchronizationActionId?.let { owner.actions.markSynchronizationPublished(seatId, it, prepared.messages) }
            },
            onFailure = owner::fail,
        )
    }

    private inline fun <T> prepare(block: () -> T): T =
        try {
            block()
        } catch (ex: Exception) {
            owner.fail(ex)
        }

    private fun preparePuzzleInitial(
        seatId: SeatId,
        actionId: String,
        gameStateId: Int,
        planner: LogicalSequencePlanner,
    ): PreparedPuzzleInitial {
        val pending = checkNotNull(owner.bridge.actionBridge(seatId).exactPending(actionId))
        val initial =
            LifecycleMessageMaterializer.puzzleInitialBundle(
                seatId,
                owner.matchId,
                planner.currentMsgId(),
                gameStateId,
                owner.bridge,
            )
        if (pending.state.kind != PendingActionKind.SYNC_ONLY) {
            val actions = checkNotNull(owner.bridge.bindInitialPuzzleHorizon(actionId, gameStateId))
            val request = LifecycleMessageMaterializer.puzzleActionsReq(initial.nextMsgId, gameStateId, seatId, actions)
            return PreparedPuzzleInitial(
                pending.state.kind,
                LifecycleMessageMaterializer.lifecycleMessages(
                    initial.messages + request.messages,
                    request.nextMsgId,
                    initial.transition,
                ),
            )
        }
        planner.setMsgId(initial.nextMsgId)
        val synchronization = prepareSynchronization(seatId, actionId, checkNotNull(initial.transition), planner)
        return PreparedPuzzleInitial(
            pending.state.kind,
            LifecycleMessageMaterializer.lifecycleMessages(
                initial.messages + synchronization.messages,
                planner.currentMsgId(),
                synchronization.transition,
            ),
            synchronization.replaces,
        )
    }

    private fun preparePuzzleReplacement(
        seatId: SeatId,
        deletedInstanceIds: List<Int>,
        actionId: String,
        gameStateId: Int,
        planner: LogicalSequencePlanner,
    ): PreparedPuzzleReplacement {
        val pending = checkNotNull(owner.bridge.actionBridge(seatId).exactPending(actionId))
        val full = owner.feed(seatId).builder.prepareFullState(checkNotNull(owner.bridge.getGame()), gameStateId)
        val gsm =
            full.result.gsm
                .toBuilder()
                .addAllDiffDeletedInstanceIds(deletedInstanceIds)
                .build()
        val state =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setMsgId(planner.currentMsgId())
                .setGameStateId(gameStateId)
                .addSystemSeatIds(seatId.value)
                .setGameStateMessage(gsm)
                .build()
        if (pending.state.kind == PendingActionKind.SYNC_ONLY) {
            val synchronization = prepareSynchronization(seatId, actionId, full.transition, planner)
            return PreparedPuzzleReplacement(
                LifecycleMessageMaterializer.lifecycleMessages(
                    listOf(state) + synchronization.messages,
                    planner.currentMsgId(),
                    synchronization.transition,
                ),
                synchronization.replaces,
                full.result.gsm.gameObjectsCount,
                full.result.gsm.zonesCount,
            )
        }
        val actions = checkNotNull(owner.bridge.bindInitialPuzzleHorizon(actionId, gameStateId))
        val request =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.ActionsAvailableReq_695e)
                .setMsgId(planner.currentMsgId() + 1)
                .setGameStateId(gameStateId)
                .addSystemSeatIds(seatId.value)
                .setActionsAvailableReq(actions)
                .setPrompt(Prompt.newBuilder().setPromptId(leyline.game.mapping.PromptIds.PASS_PRIORITY).build())
                .build()
        return PreparedPuzzleReplacement(
            LifecycleMessageMaterializer.lifecycleMessages(
                listOf(state, request),
                planner.currentMsgId() + 2,
                full.transition,
            ),
            objectCount = full.result.gsm.gameObjectsCount,
            zoneCount = full.result.gsm.zonesCount,
        )
    }

    private data class PreparedSynchronization(
        val messages: List<GREToClientMessage>,
        val transition: ProjectionTransition,
        val replaces: List<GREToClientMessage>,
    )

    private fun prepareSynchronization(
        seatId: SeatId,
        actionId: String,
        lifecycleTransition: ProjectionTransition,
        planner: LogicalSequencePlanner,
    ): PreparedSynchronization {
        val phase =
            owner
                .feed(seatId)
                .builder
                .preparePhaseTransitionDiff(
                    checkNotNull(owner.bridge.getGame()),
                    planner,
                    priorityActions = ActionsAvailableReq.getDefaultInstance(),
                    includePriorityPrompt = false,
                    priorProjection = lifecycleTransition.nextState,
                )
        val phaseTransition = checkNotNull(phase.transition)
        return PreparedSynchronization(
            phase.bundle.messages,
            lifecycleTransition.copy(
                nextState = phaseTransition.nextState.copy(revision = lifecycleTransition.expectedRevision + 1),
            ),
            owner.actions.synchronizationBatch(actionId),
        )
    }
}
