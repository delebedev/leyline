package leyline.game.bundle

import leyline.bridge.handoff.OrderRouteKind
import leyline.bridge.handoff.OrderWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.PromptIds
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.OrderReq
import wotc.mtgo.gre.external.messaging.Messages.OrderingContext

/** Value-only GRE preparation for coordinator-owned ordered-card windows. */
internal class OrderWindowMaterializer(
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
        window: OrderWindowValue,
    ): Prepared {
        val request =
            OrderReq
                .newBuilder()
                .addAllIds(window.candidates.map { candidate -> projection.requireInstanceId(candidate.forgeCardId) })
                .apply {
                    if (window.kind == OrderRouteKind.Bottom) {
                        orderingContext = OrderingContext.OrderingForBottom
                    }
                }.build()
        val sourceId = window.sourceForgeCardId?.let { source -> projection.requireInstanceId(source) } ?: 0
        val state = gameState.toBuilder().setPendingMessageCount(1).build()
        val messages =
            listOf(
                makeGRE(GREMessageType.GameStateMessage_695e, gameStateId, counter.nextMsgId()) {
                    it.gameStateMessage = state
                },
                makeGRE(GREMessageType.OrderReq_695e, gameStateId, counter.nextMsgId()) {
                    it.orderReq = request
                    it.prompt = promptWithCardId(promptId(window.kind), sourceId)
                    it.allowCancel = AllowCancel.No_a526
                    if (window.kind == OrderRouteKind.Top) it.allowUndo = true
                },
            )
        return Prepared(
            BundleBuilder.BundleResult(messages, actionGameStateId = gameStateId),
            transition,
            closesPlaybackFrame = true,
        )
    }

    private fun promptId(kind: OrderRouteKind): Int =
        when (kind) {
            OrderRouteKind.Bottom -> PromptIds.ORDER_LIBRARY_BOTTOM
            OrderRouteKind.Top -> PromptIds.ORDER_LIBRARY_TOP
        }

    private fun ProjectionState.requireInstanceId(cardId: ForgeCardId): Int =
        identities.forgeIdToInstanceId[cardId]?.value ?: error("Order card ${cardId.value} has no projected instance id")

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
