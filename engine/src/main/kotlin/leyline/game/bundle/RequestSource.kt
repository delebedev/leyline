package leyline.game.bundle

import wotc.mtgo.gre.external.messaging.Messages.ParameterType
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.PromptParameter
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

internal fun cardIdPromptParameter(numberValue: Int? = null): PromptParameter {
    val builder =
        PromptParameter
            .newBuilder()
            .setParameterName("CardId")
            .setType(ParameterType.Number)
    if (numberValue != null) builder.setNumberValue(numberValue)
    return builder.build()
}

internal fun promptWithCardId(
    promptId: Int,
    cardId: Int,
): Prompt =
    Prompt
        .newBuilder()
        .setPromptId(promptId)
        .addParameters(cardIdPromptParameter(cardId))
        .build()

internal fun SelectNReq.Builder.setSelectNInnerPrompt(promptId: Int) {
    setPrompt(
        Prompt
            .newBuilder()
            .addParameters(
                PromptParameter
                    .newBuilder()
                    .setParameterName("Parameter")
                    .setType(ParameterType.PromptId)
                    .setPromptId(promptId),
            ),
    )
}
