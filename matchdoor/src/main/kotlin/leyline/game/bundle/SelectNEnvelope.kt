package leyline.game.bundle

import leyline.game.mapping.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.ParameterType
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.PromptParameter
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

data class SelectNEnvelope(
    val req: SelectNReq,
    val prompt: Prompt,
    val allowCancel: AllowCancel = AllowCancel.None_a526,
    val gameStateAugmentation: GameStateAugmentation = GameStateAugmentation.None,
) {
    sealed interface GameStateAugmentation {
        data object None : GameStateAugmentation

        data object LookAndPick : GameStateAugmentation

        data object LearnLesson : GameStateAugmentation
    }

    companion object {
        fun default(req: SelectNReq): SelectNEnvelope =
            SelectNEnvelope(
                req = req,
                prompt = Prompt.newBuilder().setPromptId(PromptIds.SELECT_N).build(),
            )

        fun legendRule(req: SelectNReq): SelectNEnvelope =
            SelectNEnvelope(
                req = req,
                prompt =
                    Prompt
                        .newBuilder()
                        .setPromptId(PromptIds.SELECT_N_LEGEND_RULE)
                        .addParameters(
                            PromptParameter
                                .newBuilder()
                                .setParameterName("CardId")
                                .setType(ParameterType.Number),
                        ).build(),
                allowCancel = AllowCancel.No_a526,
            )

        fun revealChoose(req: SelectNReq): SelectNEnvelope =
            SelectNEnvelope(
                req = req,
                prompt = Prompt.newBuilder().setPromptId(PromptIds.SELECT_N).build(),
                allowCancel = AllowCancel.No_a526,
            )

        fun resolution(req: SelectNReq): SelectNEnvelope =
            SelectNEnvelope(
                req = req,
                prompt = stockUpPrompt(req),
                allowCancel = AllowCancel.No_a526,
                gameStateAugmentation = GameStateAugmentation.LookAndPick,
            )

        fun mutateTopBottom(req: SelectNReq): SelectNEnvelope =
            SelectNEnvelope(
                req = req,
                prompt = Prompt.newBuilder().setPromptId(PromptIds.SELECT_N).build(),
                allowCancel = AllowCancel.No_a526,
            )

        fun learnLesson(
            req: SelectNReq,
            promptId: Int,
        ): SelectNEnvelope =
            SelectNEnvelope(
                req = req,
                prompt = promptWithSourceAndCount(promptId, req),
                allowCancel = AllowCancel.Continue,
                gameStateAugmentation = GameStateAugmentation.LearnLesson,
            )

        fun staticChoice(
            req: SelectNReq,
            promptId: Int,
        ): SelectNEnvelope =
            SelectNEnvelope(
                req = req,
                prompt = promptWithSource(promptId, req),
                allowCancel = AllowCancel.No_a526,
            )

        private fun stockUpPrompt(req: SelectNReq): Prompt = promptWithSourceAndCount(PromptIds.SELECT_N_STOCK_UP, req)

        private fun promptWithSource(
            promptId: Int,
            req: SelectNReq,
        ): Prompt =
            Prompt
                .newBuilder()
                .setPromptId(promptId)
                .addParameters(
                    PromptParameter
                        .newBuilder()
                        .setParameterName("CardId")
                        .setType(ParameterType.Number)
                        .setNumberValue(req.sourceId),
                ).build()

        private fun promptWithSourceAndCount(
            promptId: Int,
            req: SelectNReq,
        ): Prompt =
            Prompt
                .newBuilder()
                .setPromptId(promptId)
                .addParameters(
                    PromptParameter
                        .newBuilder()
                        .setParameterName("CardId")
                        .setType(ParameterType.Number)
                        .setNumberValue(req.sourceId),
                ).addParameters(
                    PromptParameter
                        .newBuilder()
                        .setParameterName("CardId")
                        .setType(ParameterType.Number)
                        .setNumberValue(req.maxSel),
                ).build()
    }
}
