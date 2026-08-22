package leyline.bridge.coord

import forge.game.replacement.ReplacementEffect
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic

/**
 * Engine-thread owner of the competing-replacement choice override.
 *
 * Preserves PCHuman's automatic no-prompt behavior upstream (sole or
 * text-identical options never reach here). For the remaining distinct cases it
 * builds a typed `SelectReplacement` prompt; when the V1 self-replacement route
 * does not cover the choice the coordinator returns null so the controller falls
 * back to the inherited GUI ordering path instead of fabricating identity.
 */
class ReplacementCoordinator(
    private val bridge: InteractivePromptBridge,
) {
    fun chooseSingleReplacementEffect(possibleReplacers: List<ReplacementEffect>): ReplacementEffect? {
        val request =
            PromptRequest(
                promptType = "select_replacement",
                message = "Choose which replacement effect applies first",
                options = possibleReplacers.map { it.toString() },
                min = 1,
                max = 1,
                defaultIndex = 0,
                route = PromptRouteResolver.resolve(PromptSemantic.SelectReplacement),
            )
        return bridge.requestReplacement(request, possibleReplacers)?.handle
    }
}
