package leyline.game.bundle

import leyline.bridge.handoff.OneShotPayCostsWindowValue
import leyline.bridge.handoff.PayCostsPromptSourceValue
import leyline.bridge.handoff.PayCostsRouteKind
import leyline.bridge.handoff.TapPaymentKind
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.*

/** Value-only GRE preparation for coordinator-owned one-shot PayCosts windows. */
internal class OneShotPayCostsMaterializer(
    private val seatId: Int,
) {
    data class Prepared(
        val bundle: BundleBuilder.BundleResult,
        val transition: ProjectionTransition,
        val closesPlaybackFrame: Boolean,
    )

    fun prepare(
        gameState: GameStateMessage,
        gameStateId: Int,
        counter: MessageCounter,
        projection: ProjectionState,
        transition: ProjectionTransition,
        window: OneShotPayCostsWindowValue,
    ): Prepared {
        val messages =
            listOf(
                makeGRE(GREMessageType.GameStateMessage_695e, gameStateId, counter.nextMsgId()) {
                    it.gameStateMessage = gameState
                },
                makeGRE(GREMessageType.PayCostsReq_695e, gameStateId, counter.nextMsgId()) {
                    it.payCostsReq = payCostsRequest(window, projection)
                    it.prompt = paymentPrompt(window, projection)
                    it.allowCancel = AllowCancel.Abort
                    it.allowUndo = true
                },
            )
        return Prepared(
            BundleBuilder.BundleResult(messages, actionGameStateId = gameStateId),
            transition,
            closesPlaybackFrame = true,
        )
    }

    private fun payCostsRequest(
        window: OneShotPayCostsWindowValue,
        projection: ProjectionState,
    ): PayCostsReq {
        val selection =
            SelectNReq
                .newBuilder()
                .setMinSel(wireMinSelections(window))
                .setMaxSel(wireMaxSelections(window))
                .setContext(SelectionContext.NonManaPayment)
                .setOptionContext(OptionContext.Payment)
                .setListType(SelectionListType.Dynamic)
                .setIdType(IdType.InstanceId_ab2c)
                .setValidationType(SelectionValidationType.NonRepeatable)
                .setMinWeight(
                    when (window.kind) {
                        PayCostsRouteKind.CollectEvidence -> checkNotNull(window.minimumWeight)
                        PayCostsRouteKind.Sacrifice,
                        PayCostsRouteKind.SelectCostExileFromGrave,
                        PayCostsRouteKind.SelectCostReturnAttacker,
                        PayCostsRouteKind.StationTapCost,
                        PayCostsRouteKind.EnlistCost,
                        PayCostsRouteKind.TapPayment,
                        PayCostsRouteKind.ConvokeCost,
                        PayCostsRouteKind.ImproviseCost,
                        PayCostsRouteKind.WaterbendCost,
                        -> Int.MIN_VALUE
                    },
                ).setMaxWeight(Int.MAX_VALUE)
        window.candidates.forEach { candidate ->
            selection.addIds(projection.requireInstanceId(candidate.forgeCardId))
            selection.addWeights(candidate.weight)
        }
        return PayCostsReq
            .newBuilder()
            .setPaymentActions(ActionsAvailableReq.getDefaultInstance())
            .setEffectCostReq(
                EffectCostReq
                    .newBuilder()
                    .setEffectCostType(EffectCostType.Select_a59c)
                    .setCostSelection(selection),
            ).build()
    }

    private fun paymentPrompt(
        window: OneShotPayCostsWindowValue,
        projection: ProjectionState,
    ): Prompt =
        promptWithCardId(
            promptId(window),
            promptSourceId(window, projection),
        )

    private fun promptId(window: OneShotPayCostsWindowValue): Int =
        when (window.kind) {
            PayCostsRouteKind.Sacrifice -> PromptIds.CHOOSE_OR_COST_PAY_SACRIFICE
            PayCostsRouteKind.SelectCostExileFromGrave -> PromptIds.CHOOSE_OR_COST_PAY_EXILE_FROM_GRAVE
            PayCostsRouteKind.SelectCostReturnAttacker -> PromptIds.NINJUTSU_RETURN_UNBLOCKED_ATTACKER_COST
            PayCostsRouteKind.CollectEvidence -> PromptIds.COLLECT_EVIDENCE_COST
            PayCostsRouteKind.StationTapCost -> PromptIds.STATION_TAP_COST
            PayCostsRouteKind.EnlistCost -> PromptIds.ENLIST_TAP_COST
            PayCostsRouteKind.TapPayment -> checkNotNull(window.tapPayment).promptId
            PayCostsRouteKind.ConvokeCost,
            PayCostsRouteKind.ImproviseCost,
            PayCostsRouteKind.WaterbendCost,
            -> error("Iterative payment cannot use one-shot materializer")
        }

    private fun wireMinSelections(window: OneShotPayCostsWindowValue): Int =
        window.tapPayment
            ?.takeIf { it.kind == TapPaymentKind.TotalPower }
            ?.required
            ?: window.minSelections

    private fun wireMaxSelections(window: OneShotPayCostsWindowValue): Int =
        if (window.tapPayment?.kind == TapPaymentKind.TotalPower) Int.MAX_VALUE else window.maxSelections

    private fun promptSourceId(
        window: OneShotPayCostsWindowValue,
        projection: ProjectionState,
    ): Int =
        when (val source = window.promptSource) {
            is PayCostsPromptSourceValue.StackAbility ->
                projection.requireInstanceId(FrameIdResolver.triggerStackAbilityForgeId(source.forgeAbilityId))
            is PayCostsPromptSourceValue.StackCard -> projection.requireInstanceId(source.forgeCardId)
            null -> 0
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
