package leyline.bridge.coord

import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.SeatId
import leyline.game.bundle.GsmBuilder
import leyline.game.bundle.LifecycleMessageMaterializer
import leyline.game.bundle.markIfPrompt
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.Prompt

/** Owns preparation and ordered publication of match lifecycle output. */
internal class MatchLifecycleRuntime(
    private val owner: MatchCutCoordinator,
) {
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
        seedProjectionCursor: Boolean,
    ): Int {
        owner.registerViewer(seatId)
        return synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    val gsId = owner.counter.nextGsId()
                    val prepared =
                        prepare {
                            val deck =
                                GsmBuilder.buildDeckMessage(
                                    owner.bridge.getDeckGrpIds(seatId),
                                    owner.bridge.getCommanderGrpIds(seatId),
                                )
                            LifecycleMessageMaterializer.initialBundle(
                                seatId,
                                owner.matchId,
                                owner.counter.currentMsgId(),
                                gsId,
                                deck,
                                owner.bridge,
                                dieRollWinner = owner.bridge.dieRollWinner,
                                includeStartingPlayerPrompt = includeStartingPlayerPrompt,
                                seedProjectionCursor = seedProjectionCursor,
                            )
                        }
                    install(seatId, gsId, prepared)
                    gsId
                }
            }
        }
    }

    fun publishDealHand(
        seatId: SeatId,
        deletedInstanceIds: List<Int> = emptyList(),
    ): Int {
        owner.registerViewer(seatId)
        return synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    val gsId = owner.counter.nextGsId()
                    val prepared =
                        prepare {
                            LifecycleMessageMaterializer.dealHand(
                                owner.counter.currentMsgId(),
                                gsId,
                                owner.bridge,
                                seatId,
                                deletedInstanceIds,
                            )
                        }
                    install(seatId, gsId, prepared)
                    gsId
                }
            }
        }
    }

    fun publishDealHandMulligan(seatId: SeatId): Int {
        owner.registerViewer(seatId)
        return synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    val gsId = owner.counter.nextGsId()
                    val prepared =
                        prepare {
                            LifecycleMessageMaterializer.dealHandMulliganSeat2(
                                owner.counter.currentMsgId(),
                                gsId,
                                owner.bridge,
                            )
                        }
                    install(seatId, gsId, prepared)
                    gsId
                }
            }
        }
    }

    fun publishMulliganRequest(
        seatId: SeatId,
        mulliganCount: Int,
        numCards: Int,
    ): Int {
        owner.registerViewer(seatId)
        return synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    val gsId = owner.counter.nextGsId()
                    val prepared =
                        prepare {
                            LifecycleMessageMaterializer.mulliganReqSeat1(
                                owner.counter.currentMsgId(),
                                gsId,
                                owner.bridge,
                                mulliganCount,
                                numCards,
                            )
                        }
                    install(seatId, gsId, prepared)
                    gsId
                }
            }
        }
    }

    fun publishPuzzleInitial(
        seatId: SeatId,
        actionId: String,
    ): PuzzleInitialPublication {
        owner.registerViewer(seatId)
        return synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    val gsId = owner.counter.nextGsId()
                    val prepared = prepare { preparePuzzleInitial(seatId, actionId, gsId) }
                    install(seatId, gsId, prepared.messages, prepared.replaces.orEmpty(), actionId.takeIf { prepared.replaces != null })
                    PuzzleInitialPublication(
                        gameStateId = gsId,
                        kind = prepared.kind,
                        deliveryBoundaryMsgId = prepared.messages.nextMsgId,
                    )
                }
            }
        }
    }

    fun publishPuzzleReplacement(
        seatId: SeatId,
        deletedInstanceIds: List<Int>,
        actionId: String,
    ): PuzzleReplacementPublication {
        owner.registerViewer(seatId)
        return synchronized(owner.counter) {
            synchronized(owner.bridge.projectionBuildLock) {
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    val gsId = owner.counter.nextGsId()
                    val prepared = prepare { preparePuzzleReplacement(seatId, deletedInstanceIds, actionId, gsId) }
                    install(seatId, gsId, prepared.messages, prepared.replaces.orEmpty(), actionId.takeIf { prepared.replaces != null })
                    PuzzleReplacementPublication(
                        gameStateId = gsId,
                        objectCount = prepared.objectCount,
                        zoneCount = prepared.zoneCount,
                    )
                }
            }
        }
    }

    private fun install(
        seatId: SeatId,
        gameStateId: Int,
        prepared: LifecycleMessageMaterializer.LifecycleMessages,
        replaces: List<GREToClientMessage> = emptyList(),
        synchronizationActionId: String? = null,
    ) {
        owner.counter.setMsgId(prepared.nextMsgId)
        owner.cutInstaller.install(
            feed = owner.feed(seatId),
            cut = PreparedCut(prepared.messages, prepared.transition, closesPlaybackFrame = false),
            replaces = replaces,
            onInstalled = {
                owner.counter.markGameStateGsId(gameStateId)
                prepared.messages.forEach {
                    markIfPrompt(owner.counter, it.type, it.gameStateId, it.msgId)
                }
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
    ): PreparedPuzzleInitial {
        val pending = checkNotNull(owner.bridge.actionBridge(seatId).exactPending(actionId))
        val initial =
            LifecycleMessageMaterializer.puzzleInitialBundle(
                seatId,
                owner.matchId,
                owner.counter.currentMsgId(),
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
        owner.counter.setMsgId(initial.nextMsgId)
        val synchronization = prepareSynchronization(seatId, actionId, checkNotNull(initial.transition))
        return PreparedPuzzleInitial(
            pending.state.kind,
            LifecycleMessageMaterializer.lifecycleMessages(
                initial.messages + synchronization.messages,
                owner.counter.currentMsgId(),
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
                .setMsgId(owner.counter.currentMsgId())
                .setGameStateId(gameStateId)
                .addSystemSeatIds(seatId.value)
                .setGameStateMessage(gsm)
                .build()
        if (pending.state.kind == PendingActionKind.SYNC_ONLY) {
            val synchronization = prepareSynchronization(seatId, actionId, full.transition)
            return PreparedPuzzleReplacement(
                LifecycleMessageMaterializer.lifecycleMessages(
                    listOf(state) + synchronization.messages,
                    owner.counter.currentMsgId(),
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
                .setMsgId(owner.counter.currentMsgId() + 1)
                .setGameStateId(gameStateId)
                .addSystemSeatIds(seatId.value)
                .setActionsAvailableReq(actions)
                .setPrompt(Prompt.newBuilder().setPromptId(leyline.game.mapping.PromptIds.PASS_PRIORITY).build())
                .build()
        return PreparedPuzzleReplacement(
            LifecycleMessageMaterializer.lifecycleMessages(
                listOf(state, request),
                owner.counter.currentMsgId() + 2,
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
    ): PreparedSynchronization {
        val phase =
            owner
                .feed(seatId)
                .builder
                .preparePhaseTransitionDiff(
                    checkNotNull(owner.bridge.getGame()),
                    owner.counter,
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
