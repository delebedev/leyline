package leyline.game.bundle

import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.SelectNPromptRoute
import leyline.game.mapping.PromptIds
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

internal fun SelectNPromptRoute.envelope(req: SelectNReq): SelectNEnvelope = SelectNEnvelope.resolution(req)

internal fun SelectNPromptRoute.configureInnerPrompt(
    builder: SelectNReq.Builder,
    prompt: InteractivePromptBridge.PendingPrompt,
    bridge: GameBridge,
) {
    builder.setSourceIdIfPresent(prompt, bridge)
    builder.setSelectNInnerPrompt(PromptIds.SELECT_N_INNER_PARAMETER)
}
