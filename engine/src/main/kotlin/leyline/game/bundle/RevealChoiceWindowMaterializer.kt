package leyline.game.bundle

import leyline.bridge.handoff.RevealChoiceWindowValue
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

/** Value-only GRE preparation for one reveal-backed SelectN window. */
internal class RevealChoiceWindowMaterializer(
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
        counter: LogicalSequencePlanner,
        projection: ProjectionState,
        transition: ProjectionTransition,
        window: RevealChoiceWindowValue,
    ): Prepared {
        val request = buildRequest(window, projection)
        val state = gameState.toBuilder().setPendingMessageCount(1).build()
        val messages =
            listOf(
                makeGRE(GREMessageType.GameStateMessage_695e, gameStateId, counter.nextMsgId()) {
                    it.gameStateMessage = state
                },
                makeGRE(GREMessageType.SelectNreq, gameStateId, counter.nextMsgId()) {
                    it.selectNReq = request
                    it.prompt = Prompt.newBuilder().setPromptId(PromptIds.SELECT_N).build()
                    it.allowCancel = AllowCancel.No_a526
                },
            )
        return Prepared(BundleBuilder.BundleResult(messages, actionGameStateId = gameStateId), transition, true)
    }

    private fun buildRequest(
        window: RevealChoiceWindowValue,
        projection: ProjectionState,
    ): SelectNReq =
        SelectNReq
            .newBuilder()
            .setContext(SelectionContext.Resolution_a163)
            .setListType(SelectionListType.Dynamic)
            .setValidationType(SelectionValidationType.NonRepeatable)
            .setOptionContext(OptionContext.Resolution_a9d7)
            .setMinWeight(Int.MIN_VALUE)
            .setMaxWeight(Int.MAX_VALUE)
            .setIdType(IdType.InstanceId_ab2c)
            .addAllIds(window.candidates.map { projection.requireInstanceId(it.forgeCardId) })
            .addAllUnfilteredIds(window.fullRevealCardIds.map { projection.requireInstanceId(it) })
            .setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_N))
            .apply {
                if (window.candidates.isNotEmpty()) {
                    minSel = window.min
                    maxSel = window.max
                }
                window.sourceForgeCardId?.let { sourceId = projection.requireInstanceId(it) }
            }.build()

    private fun ProjectionState.requireInstanceId(cardId: ForgeCardId): Int =
        identities.forgeIdToInstanceId[cardId]?.value ?: error("RevealChoice card ${cardId.value} has no projected instance id")

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
