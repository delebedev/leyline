package leyline.match

import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.game.bundle.PromptRouteFamily
import leyline.game.bundle.PromptSemanticRouteMetadata
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
    ) : ClassifiedPrompt

    data class Targeting(
        override val pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ) : ClassifiedPrompt

    data class Search(
        override val pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ) : ClassifiedPrompt

    data class Order(
        override val pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ) : ClassifiedPrompt

    data class AutoResolve(
        override val pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ) : ClassifiedPrompt
}

object PromptClassifier {
    fun classify(pendingPrompt: InteractivePromptBridge.PendingPrompt): ClassifiedPrompt {
        val req = pendingPrompt.request
        return classifyBySemantic(pendingPrompt, req) ?: classifyGeneric(pendingPrompt, req)
    }

    private fun classifyBySemantic(
        p: InteractivePromptBridge.PendingPrompt,
        req: PromptRequest,
    ): ClassifiedPrompt? {
        val route = PromptSemanticRouteMetadata.route(req.semantic) ?: return null
        return when (route.family) {
            PromptRouteFamily.Grouping -> ClassifiedPrompt.Grouping(p, route.groupingContext ?: error("missing grouping context"))
            PromptRouteFamily.ModalChoice -> ClassifiedPrompt.ModalChoice(p)
            PromptRouteFamily.SelectN,
            PromptRouteFamily.PayCosts,
            -> ClassifiedPrompt.SelectN(p)
            PromptRouteFamily.Search -> ClassifiedPrompt.Search(p)
            PromptRouteFamily.Order -> ClassifiedPrompt.Order(p)
            PromptRouteFamily.AutoResolve -> ClassifiedPrompt.AutoResolve(p)
        }
    }

    private fun classifyGeneric(
        p: InteractivePromptBridge.PendingPrompt,
        req: PromptRequest,
    ): ClassifiedPrompt =
        when {
            req.candidateRefs.isNotEmpty() -> ClassifiedPrompt.Targeting(p)
            else -> ClassifiedPrompt.AutoResolve(p)
        }
}
