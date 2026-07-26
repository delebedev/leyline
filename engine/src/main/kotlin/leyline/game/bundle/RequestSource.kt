package leyline.game.bundle

import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.mapping.FrameIdResolver
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.ParameterType
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.PromptParameter
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

internal data class PromptSource(
    val sourceInstanceId: Int = 0,
    val sourceCardInstanceId: Int = 0,
) {
    val hasSource: Boolean get() = sourceInstanceId != 0
}

internal object PromptSourceResolver {
    fun resolve(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
        fallbackSourceEntityId: Int? = null,
        idResolver: (ForgeCardId) -> InstanceId = bridge::getOrAllocInstanceId,
    ): PromptSource {
        val sourceCardInstanceId =
            (prompt.request.sourceEntityId ?: fallbackSourceEntityId)
                ?.let { idResolver(ForgeCardId(it)).value }
                ?: 0
        val sourceInstanceId =
            if (prompt.request.isTriggeredAbility && prompt.request.forgeAbilityId != 0) {
                idResolver(FrameIdResolver.triggerStackAbilityForgeId(prompt.request.forgeAbilityId)).value
            } else {
                sourceCardInstanceId
            }
        return PromptSource(
            sourceInstanceId = sourceInstanceId,
            sourceCardInstanceId = sourceCardInstanceId,
        )
    }
}

internal fun sourceInstanceId(
    prompt: InteractivePromptBridge.PendingPrompt,
    bridge: GameBridge,
    idResolver: (ForgeCardId) -> InstanceId = bridge::getOrAllocInstanceId,
): Int = PromptSourceResolver.resolve(prompt, bridge, idResolver = idResolver).sourceInstanceId

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

internal fun SelectNReq.Builder.setSourceIdIfPresent(
    prompt: InteractivePromptBridge.PendingPrompt,
    bridge: GameBridge,
    idResolver: (ForgeCardId) -> InstanceId = bridge::getOrAllocInstanceId,
) {
    val source = PromptSourceResolver.resolve(prompt, bridge, idResolver = idResolver)
    if (source.hasSource) setSourceId(source.sourceInstanceId)
}

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
