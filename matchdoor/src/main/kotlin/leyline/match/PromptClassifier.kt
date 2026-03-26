package leyline.match

import leyline.bridge.InteractivePromptBridge
import leyline.bridge.PromptSemantic
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
            Discard,
        }
    }

    data class Targeting(
        override val pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ) : ClassifiedPrompt

    data class Search(
        override val pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ) : ClassifiedPrompt

    data class AutoResolve(
        override val pendingPrompt: InteractivePromptBridge.PendingPrompt,
    ) : ClassifiedPrompt
}

object PromptClassifier {
    fun classify(pendingPrompt: InteractivePromptBridge.PendingPrompt): ClassifiedPrompt {
        val req = pendingPrompt.request
        return when {
            req.semantic == PromptSemantic.GroupingSurveil -> {
                ClassifiedPrompt.Grouping(pendingPrompt, GroupingContext.Surveil)
            }

            req.semantic == PromptSemantic.GroupingScry -> {
                ClassifiedPrompt.Grouping(pendingPrompt, GroupingContext.Scry_a0f6)
            }

            req.semantic == PromptSemantic.ModalChoice -> ClassifiedPrompt.ModalChoice(pendingPrompt)
            req.semantic == PromptSemantic.SelectNLegendRule -> ClassifiedPrompt.SelectN(
                pendingPrompt,
                ClassifiedPrompt.SelectN.Reason.LegendRule,
            )
            req.semantic == PromptSemantic.Search -> ClassifiedPrompt.Search(pendingPrompt)
            req.semantic == PromptSemantic.SelectNDiscard -> ClassifiedPrompt.SelectN(
                pendingPrompt,
                ClassifiedPrompt.SelectN.Reason.Discard,
            )
            req.candidateRefs.isNotEmpty() -> ClassifiedPrompt.Targeting(pendingPrompt)
            else -> ClassifiedPrompt.AutoResolve(pendingPrompt)
        }
    }
}
