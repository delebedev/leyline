package leyline.game.bundle

import leyline.bridge.handoff.BlockingInteraction
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.*

/** Prepares the source-bound text-input compatibility envelope without a catalog option list. */
internal object CardNameInteractionMaterializer {
    fun prepare(
        seatId: Int,
        prior: ProjectionState,
        counter: LogicalSequencePlanner,
        interaction: BlockingInteraction.CardName,
    ): BlockingInteractionMaterializer.Prepared {
        val editor = prior.editor()
        val sourceId = editor.identities.getOrAlloc(interaction.sourceId).value
        val prompt = Prompt.newBuilder()

        fun parameter(
            name: String,
            value: String,
        ) {
            prompt.addParameters(
                PromptParameter
                    .newBuilder()
                    .setParameterName(name)
                    .setType(ParameterType.NonLocalizedString)
                    .setStringValue(value),
            )
        }
        parameter("choiceKind", "cardName")
        parameter("instruction", interaction.instruction)
        interaction.validationMessage?.let { parameter("validationError", it) }
        val link = counter.nextGameStateLink()
        val pending =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(link.gsId)
                .setPrevGameStateId(link.prevGsId)
                .setPendingMessageCount(1)
                .setUpdate(GameStateUpdate.SendAndRecord)
                .build()
        val state =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .addSystemSeatIds(seatId)
                .setGameStateId(link.gsId)
                .setMsgId(counter.nextMsgId())
                .setGameStateMessage(pending)
                .build()
        val request =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.StringInputReq_695e)
                .addSystemSeatIds(seatId)
                .setGameStateId(link.gsId)
                .setMsgId(counter.nextMsgId())
                .setStringInputReq(StringInputReq.newBuilder().setSourceId(sourceId))
                .setPrompt(prompt)
                .setAllowCancel(AllowCancel.No_a526)
                .build()
        val next = editor.freeze()
        val transition = if (next.copy(revision = prior.revision) != prior) ProjectionTransition(prior.revision, next) else null
        return BlockingInteractionMaterializer.Prepared(
            BundleBuilder.BundleResult(listOf(state, request), actionGameStateId = link.gsId),
            transition,
        )
    }
}
