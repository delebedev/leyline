package leyline.match

import leyline.bridge.InteractivePromptBridge
import leyline.bridge.PromptRequest
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
        val reason: Reason,
    ) : ClassifiedPrompt {
        enum class Reason {
            LegendRule,
        }
    }

    data class Targeting(
        override val pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ) : ClassifiedPrompt

    data class AutoResolve(
        override val pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ) : ClassifiedPrompt
}

object PromptClassifier {
    fun classify(pendingPrompt: InteractivePromptBridge.PendingPrompt): ClassifiedPrompt {
        val req = pendingPrompt.request
        val groupingContext = detectGroupingContext(req)
        if (groupingContext != null) {
            return ClassifiedPrompt.Grouping(pendingPrompt, groupingContext)
        }

        return when {
            req.promptType == "modal" -> ClassifiedPrompt.ModalChoice(pendingPrompt)
            req.promptType == "legend_rule" -> ClassifiedPrompt.SelectN(
                pendingPrompt,
                ClassifiedPrompt.SelectN.Reason.LegendRule,
            )
            req.candidateRefs.isNotEmpty() -> ClassifiedPrompt.Targeting(pendingPrompt)
            else -> ClassifiedPrompt.AutoResolve(pendingPrompt)
        }
    }

    private fun detectGroupingContext(req: PromptRequest): GroupingContext? =
        when {
            req.message.startsWith("Surveil:") -> GroupingContext.Surveil
            req.message.startsWith("Scry:") -> GroupingContext.Scry_a0f6
            else -> null
        }
}
