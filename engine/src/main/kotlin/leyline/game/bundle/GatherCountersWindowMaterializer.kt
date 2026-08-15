package leyline.game.bundle

import leyline.bridge.handoff.GatherCountersWindowValue
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.*

/** Value-only GRE preparation for the bounded GatherCounters PayCosts row. */
internal class GatherCountersWindowMaterializer(
    private val seatId: Int,
) {
    fun prepare(
        gameState: GameStateMessage,
        gameStateId: Int,
        counter: MessageCounter,
        projection: ProjectionState,
        transition: ProjectionTransition,
        window: GatherCountersWindowValue,
    ): PreparedPayCostsCut {
        val destinationId =
            projection.requireInstanceId(FrameIdResolver.triggerStackAbilityForgeId(window.promptSource.forgeAbilityId))
        val payCosts =
            PayCostsReq
                .newBuilder()
                .setPaymentActions(ActionsAvailableReq.getDefaultInstance())
                .setEffectCostReq(
                    EffectCostReq
                        .newBuilder()
                        .setEffectCostType(EffectCostType.GatherCounters)
                        .setGatherReq(
                            GatherReq
                                .newBuilder()
                                .setDestinationId(destinationId)
                                .setAmountToGather(window.amountToGather)
                                .also { gather ->
                                    window.sources.forEach { source ->
                                        gather.addSources(
                                            GatherSource
                                                .newBuilder()
                                                .setSourceId(projection.requireInstanceId(source.forgeCardId))
                                                .setMaxAmount(source.maxAmount),
                                        )
                                    }
                                },
                        ),
                ).build()
        val messages =
            listOf(
                makeGRE(GREMessageType.GameStateMessage_695e, gameStateId, counter.nextMsgId()) {
                    it.gameStateMessage = gameState
                },
                makeGRE(GREMessageType.PayCostsReq_695e, gameStateId, counter.nextMsgId()) {
                    it.payCostsReq = payCosts
                    it.prompt = promptWithCardId(PromptIds.GATHER_COUNTERS, destinationId)
                    it.allowCancel = AllowCancel.Abort
                    it.allowUndo = true
                },
            )
        return PreparedPayCostsCut(
            BundleBuilder.BundleResult(messages, actionGameStateId = gameStateId),
            transition,
            closesPlaybackFrame = true,
        )
    }

    private fun ProjectionState.requireInstanceId(cardId: leyline.bridge.types.ForgeCardId): Int =
        identities.forgeIdToInstanceId[cardId]?.value ?: error("PayCosts card ${cardId.value} has no projected instance id")

    private fun makeGRE(
        type: GREMessageType,
        gameStateId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage =
        GREToClientMessage
            .newBuilder()
            .setType(type)
            .setMsgId(msgId)
            .setGameStateId(gameStateId)
            .addSystemSeatIds(seatId)
            .also(configure)
            .build()
}
