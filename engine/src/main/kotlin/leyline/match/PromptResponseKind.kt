package leyline.match

import leyline.bridge.handoff.ResolvedPromptRoute

/** Client response families accepted by a bound prompt route. */
internal enum class PromptResponseKind {
    Group,
    ModalChoice,
    SelectN,
    PayCosts,
    Search,
    Order,
    Targeting,
}

/** Pure route/response contract used before any response side effects run. */
internal fun ResolvedPromptRoute.accepts(response: PromptResponseKind): Boolean =
    when (response) {
        PromptResponseKind.Group -> this is ResolvedPromptRoute.Grouping
        PromptResponseKind.ModalChoice -> this is ResolvedPromptRoute.ModalChoice
        PromptResponseKind.SelectN -> this is ResolvedPromptRoute.SelectN
        PromptResponseKind.PayCosts -> this is ResolvedPromptRoute.PayCosts
        PromptResponseKind.Search -> this is ResolvedPromptRoute.Search
        PromptResponseKind.Order -> this is ResolvedPromptRoute.Order
        PromptResponseKind.Targeting -> this is ResolvedPromptRoute.Targeting
    }
