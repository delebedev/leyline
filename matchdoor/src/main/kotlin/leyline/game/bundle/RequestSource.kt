package leyline.game.bundle

import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.types.ForgeCardId
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
    ): PromptSource {
        val sourceCardInstanceId =
            (prompt.request.sourceEntityId ?: fallbackSourceEntityId)
                ?.let { bridge.getOrAllocInstanceId(ForgeCardId(it)).value }
                ?: 0
        val sourceInstanceId =
            if (prompt.request.isTriggeredAbility && prompt.request.forgeAbilityId != 0) {
                bridge.getOrAllocInstanceId(FrameIdResolver.triggerStackAbilityForgeId(prompt.request.forgeAbilityId)).value
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
): Int = PromptSourceResolver.resolve(prompt, bridge).sourceInstanceId

internal fun SelectNReq.Builder.setSourceIdIfPresent(
    prompt: InteractivePromptBridge.PendingPrompt,
    bridge: GameBridge,
) {
    val source = PromptSourceResolver.resolve(prompt, bridge)
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
