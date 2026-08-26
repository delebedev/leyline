package leyline.bridge.coord

import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.SeatId
import leyline.game.bundle.GsmBuilder
import leyline.game.bundle.LifecycleMessageMaterializer
import leyline.game.bundle.markIfPrompt
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
                    val deck =
                        GsmBuilder.buildDeckMessage(
                            owner.bridge.getDeckGrpIds(seatId),
                            owner.bridge.getCommanderGrpIds(seatId),
                        )
                    install(
                        seatId,
                        gsId,
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
                        ),
                    )
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
                    install(
                        seatId,
                        gsId,
                        LifecycleMessageMaterializer.dealHand(
                            owner.counter.currentMsgId(),
                            gsId,
                            owner.bridge,
                            seatId,
                            deletedInstanceIds,
                        ),
                    )
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
                    install(
                        seatId,
                        gsId,
                        LifecycleMessageMaterializer.dealHandMulliganSeat2(
                            owner.counter.currentMsgId(),
                            gsId,
                            owner.bridge,
                        ),
                    )
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
                    install(
                        seatId,
                        gsId,
                        LifecycleMessageMaterializer.mulliganReqSeat1(
                            owner.counter.currentMsgId(),
                            gsId,
                            owner.bridge,
                            mulliganCount,
                            numCards,
                        ),
                    )
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
                    val pending =
                        checkNotNull(owner.bridge.actionBridge(seatId).exactPending(actionId)) {
                            "Puzzle action window is no longer pending"
                        }
                    val initial =
                        LifecycleMessageMaterializer.puzzleInitialBundle(
                            seatId,
                            owner.matchId,
                            owner.counter.currentMsgId(),
                            gsId,
                            owner.bridge,
                        )
                    val deliveryBoundaryMsgId =
                        if (pending.state.kind == PendingActionKind.SYNC_ONLY) {
                            install(seatId, gsId, initial)
                            owner.bridge.bindInitialPuzzleHorizon(actionId, gsId)
                            initial.nextMsgId
                        } else {
                            val actions = checkNotNull(owner.bridge.bindInitialPuzzleHorizon(actionId, gsId))
                            val request =
                                LifecycleMessageMaterializer.puzzleActionsReq(
                                    initial.nextMsgId,
                                    gsId,
                                    seatId,
                                    actions,
                                )
                            install(
                                seatId,
                                gsId,
                                LifecycleMessageMaterializer.lifecycleMessages(
                                    initial.messages + request.messages,
                                    request.nextMsgId,
                                    initial.transition,
                                ),
                            )
                            request.nextMsgId
                        }
                    PuzzleInitialPublication(
                        gameStateId = gsId,
                        kind = pending.state.kind,
                        deliveryBoundaryMsgId = deliveryBoundaryMsgId,
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
                    val prepared = owner.feed(seatId).builder.prepareFullState(checkNotNull(owner.bridge.getGame()), gsId)
                    val actions = owner.bridge.bindInitialPuzzleHorizon(actionId, gsId)
                    val gsm =
                        prepared.result.gsm
                            .toBuilder()
                            .addAllDiffDeletedInstanceIds(deletedInstanceIds)
                            .build()
                    val messages =
                        buildList {
                            add(
                                GREToClientMessage
                                    .newBuilder()
                                    .setType(GREMessageType.GameStateMessage_695e)
                                    .setMsgId(owner.counter.currentMsgId())
                                    .setGameStateId(gsId)
                                    .addSystemSeatIds(seatId.value)
                                    .setGameStateMessage(gsm)
                                    .build(),
                            )
                            actions?.let {
                                add(
                                    GREToClientMessage
                                        .newBuilder()
                                        .setType(GREMessageType.ActionsAvailableReq_695e)
                                        .setMsgId(owner.counter.currentMsgId() + 1)
                                        .setGameStateId(gsId)
                                        .addSystemSeatIds(seatId.value)
                                        .setActionsAvailableReq(it)
                                        .setPrompt(Prompt.newBuilder().setPromptId(leyline.game.mapping.PromptIds.PASS_PRIORITY).build())
                                        .build(),
                                )
                            }
                        }
                    install(
                        seatId,
                        gsId,
                        LifecycleMessageMaterializer.lifecycleMessages(
                            messages,
                            owner.counter.currentMsgId() + messages.size,
                            prepared.transition,
                        ),
                    )
                    PuzzleReplacementPublication(
                        gameStateId = gsId,
                        objectCount = prepared.result.gsm.gameObjectsCount,
                        zoneCount = prepared.result.gsm.zonesCount,
                    )
                }
            }
        }
    }

    private fun install(
        seatId: SeatId,
        gameStateId: Int,
        prepared: LifecycleMessageMaterializer.LifecycleMessages,
    ) {
        owner.counter.setMsgId(prepared.nextMsgId)
        owner.cutInstaller.install(
            feed = owner.feed(seatId),
            cut = PreparedCut(prepared.messages, prepared.transition, closesPlaybackFrame = false),
            onInstalled = {
                owner.counter.markGameStateGsId(gameStateId)
                prepared.messages.forEach {
                    markIfPrompt(owner.counter, it.type, it.gameStateId, it.msgId)
                }
            },
            onFailure = owner::fail,
        )
    }
}
