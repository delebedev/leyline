package leyline.bridge.handoff

import forge.game.spellability.SpellAbility

/** Value request evaluated by an engine-local prompt advisor. */
sealed interface PromptAdviceRequest {
    data class StaticColors(
        val allowedIds: List<Int>,
        val min: Int,
        val max: Int,
    ) : PromptAdviceRequest

    data class SacrificeCost(
        val selectableIds: List<Int>,
        val min: Int,
        val max: Int,
    ) : PromptAdviceRequest

    data class SelectTargets(
        val selectableIds: Set<Int>,
        val min: Int,
        val max: Int,
    ) : PromptAdviceRequest

    data class ModalChoice(
        val modalGrpIds: List<Int>,
    ) : PromptAdviceRequest
}

/**
 * Engine-domain extension point for headless advisors.
 *
 * The match side submits [PromptAdviceRequest] values; the bridge invokes the
 * advisor beside the retained ability and returns only selected ids.
 */
fun interface PromptAbilityAdvisor {
    fun advise(
        ability: SpellAbility,
        prompt: PromptRequest,
        request: PromptAdviceRequest,
    ): List<Int>?
}
