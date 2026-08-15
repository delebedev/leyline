package leyline.match

import leyline.bridge.handoff.ResolvedPromptRoute

/** Client response families accepted by a bound prompt route. */
internal enum class PromptResponseKind {
    SelectN,
    EffectCost,
}

/** Pure route/response contract used before any response side effects run. */
internal fun ResolvedPromptRoute.accepts(response: PromptResponseKind): Boolean =
    when (response) {
        PromptResponseKind.SelectN ->
            this is ResolvedPromptRoute.UnclassifiedEntityChoice || this is ResolvedPromptRoute.RevealChoice
        PromptResponseKind.EffectCost ->
            this is ResolvedPromptRoute.UnclassifiedEntityChoice ||
                this is ResolvedPromptRoute.UnclassifiedCandidate
    }
