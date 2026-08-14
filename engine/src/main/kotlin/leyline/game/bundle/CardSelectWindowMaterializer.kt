package leyline.game.bundle

import leyline.bridge.handoff.CardSelectKind
import leyline.bridge.handoff.CardSelectWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.PromptIds
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType

/** Value-only GRE preparation for coordinator-owned card-backed SelectN windows. */
internal class CardSelectWindowMaterializer(
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
        window: CardSelectWindowValue,
    ): Prepared {
        val request = buildRequest(window, projection)
        val envelope = envelope(window.kind, request)
        val state = gameState.toBuilder().setPendingMessageCount(1).build()
        val messages =
            listOf(
                makeGRE(GREMessageType.GameStateMessage_695e, gameStateId, counter.nextMsgId()) {
                    it.gameStateMessage = state
                },
                makeGRE(GREMessageType.SelectNreq, gameStateId, counter.nextMsgId()) {
                    it.selectNReq = envelope.req
                    it.prompt = envelope.prompt
                    if (envelope.allowCancel != AllowCancel.None_a526) it.allowCancel = envelope.allowCancel
                },
            )
        return Prepared(BundleBuilder.BundleResult(messages, actionGameStateId = gameStateId), transition, true)
    }

    private fun buildRequest(
        window: CardSelectWindowValue,
        projection: ProjectionState,
    ): SelectNReq {
        val discard = window.kind == CardSelectKind.Discard
        return SelectNReq
            .newBuilder()
            .setContext(if (discard) SelectionContext.Discard_a163 else SelectionContext.Resolution_a163)
            .setListType(if (discard) SelectionListType.Static else SelectionListType.Dynamic)
            .setValidationType(SelectionValidationType.NonRepeatable)
            .setOptionContext(if (discard) OptionContext.Payment else OptionContext.Resolution_a9d7)
            .setMinWeight(Int.MIN_VALUE)
            .setMaxWeight(Int.MAX_VALUE)
            .setIdType(IdType.InstanceId_ab2c)
            .setMinSel(window.min)
            .setMaxSel(window.max)
            .addAllIds(window.candidates.map { projection.requireInstanceId(it.forgeCardId) })
            .apply {
                window.sourceForgeCardId?.let { sourceId = projection.requireInstanceId(it) }
                when (window.kind) {
                    CardSelectKind.LegendRule -> {
                        prompt = Prompt.getDefaultInstance()
                        sourceId = PromptIds.SELECT_N_LEGEND_RULE_SOURCE
                    }
                    CardSelectKind.Discard -> prompt = Prompt.newBuilder().setPromptId(PromptIds.DISCARD_COST).build()
                    CardSelectKind.Suspect -> setSelectNInnerPrompt(PromptIds.SELECT_N_INNER_PARAMETER)
                    CardSelectKind.SacrificeEffect,
                    CardSelectKind.MutateTopBottom,
                    -> prompt = Prompt.newBuilder().setPromptId(PromptIds.SELECT_N).build()
                }
            }.build()
    }

    private fun envelope(
        kind: CardSelectKind,
        request: SelectNReq,
    ): SelectNEnvelope =
        when (kind) {
            CardSelectKind.LegendRule -> SelectNEnvelope.legendRule(request)
            CardSelectKind.Discard,
            CardSelectKind.SacrificeEffect,
            -> SelectNEnvelope.default(request)
            CardSelectKind.Suspect -> SelectNEnvelope.suspectChoice(request)
            CardSelectKind.MutateTopBottom -> SelectNEnvelope.mutateTopBottom(request)
        }

    private fun ProjectionState.requireInstanceId(cardId: ForgeCardId): Int =
        identities.forgeIdToInstanceId[cardId]?.value ?: error("CardSelect card ${cardId.value} has no projected instance id")

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
