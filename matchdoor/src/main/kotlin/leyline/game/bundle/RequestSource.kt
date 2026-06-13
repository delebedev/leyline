package leyline.game.bundle

import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.FrameIdResolver
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.ParameterType
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.PromptParameter
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

internal fun sourceInstanceId(
    prompt: InteractivePromptBridge.PendingPrompt,
    bridge: GameBridge,
): Int {
    if (prompt.request.isTriggeredAbility && prompt.request.forgeAbilityId != 0) {
        return bridge.getOrAllocInstanceId(FrameIdResolver.triggerStackAbilityForgeId(prompt.request.forgeAbilityId)).value
    }
    val sourceEntityId = prompt.request.sourceEntityId ?: return 0
    return bridge.getOrAllocInstanceId(ForgeCardId(sourceEntityId)).value
}

internal fun SelectNReq.Builder.setSourceIdIfPresent(
    prompt: InteractivePromptBridge.PendingPrompt,
    bridge: GameBridge,
) {
    val sourceInstanceId = sourceInstanceId(prompt, bridge)
    if (sourceInstanceId != 0) setSourceId(sourceInstanceId)
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
