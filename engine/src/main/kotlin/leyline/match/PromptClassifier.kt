package leyline.match

import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.OrderRouteKind
import leyline.bridge.handoff.PayCostsPromptRoute
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.SelectNPromptRoute
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext

/**
 * Classifies raw engine prompts into the protocol interaction they should drive.
 *
 * Phase 1 keeps current behavior and heuristics intact; it only centralizes them
 * so transport routing no longer depends on ad hoc branch order in handlers.
 */
sealed interface ClassifiedPrompt {
    val pendingPrompt: InteractivePromptBridge.PendingPrompt

    data class Grouping(
        override val pendingPrompt: InteractivePromptBridge.PendingPrompt,
        val context: GroupingContext,
    ) : ClassifiedPrompt

    data class ModalChoice(
        override val pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ) : ClassifiedPrompt

    data class SelectN(
        override val pendingPrompt: InteractivePromptBridge.PendingPrompt,
        val route: SelectNPromptRoute,
    ) : ClassifiedPrompt

    data class PayCosts(
        override val pendingPrompt: InteractivePromptBridge.PendingPrompt,
        val route: PayCostsPromptRoute,
    ) : ClassifiedPrompt

    data class Targeting(
        override val pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ) : ClassifiedPrompt

    data class Search(
        override val pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ) : ClassifiedPrompt

    data class Order(
        override val pendingPrompt: InteractivePromptBridge.PendingPrompt,
        val kind: OrderRouteKind,
    ) : ClassifiedPrompt

    data class AutoResolve(
        override val pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ) : ClassifiedPrompt
}

object PromptClassifier {
    fun classify(pendingPrompt: InteractivePromptBridge.PendingPrompt): ClassifiedPrompt =
        when (val route = pendingPrompt.request.route) {
            is ResolvedPromptRoute.Grouping -> ClassifiedPrompt.Grouping(pendingPrompt, route.context)
            is ResolvedPromptRoute.ModalChoice -> ClassifiedPrompt.ModalChoice(pendingPrompt)
            is ResolvedPromptRoute.SelectN -> ClassifiedPrompt.SelectN(pendingPrompt, route.descriptor)
            is ResolvedPromptRoute.PayCosts -> ClassifiedPrompt.PayCosts(pendingPrompt, route.descriptor)
            is ResolvedPromptRoute.Search -> ClassifiedPrompt.Search(pendingPrompt)
            is ResolvedPromptRoute.Order -> ClassifiedPrompt.Order(pendingPrompt, route.kind)
            is ResolvedPromptRoute.Targeting -> ClassifiedPrompt.Targeting(pendingPrompt)
            is ResolvedPromptRoute.AutoResolve -> ClassifiedPrompt.AutoResolve(pendingPrompt)
        }
}
