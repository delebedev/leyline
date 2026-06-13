package leyline.match

import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
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
        val reason: SelectNReason,
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

    // Exhaustive on PromptSemantic — adding a new variant fails compile until classified.
    private fun classifyBySemantic(
        p: InteractivePromptBridge.PendingPrompt,
        req: PromptRequest,
    ): ClassifiedPrompt? {
        selectNReason(req.semantic)?.let { return ClassifiedPrompt.SelectN(p, it) }
        return when (req.semantic) {
            PromptSemantic.GroupingSurveil -> ClassifiedPrompt.Grouping(p, GroupingContext.Surveil)
            PromptSemantic.GroupingScry -> ClassifiedPrompt.Grouping(p, GroupingContext.Scry_a0f6)
            PromptSemantic.ModalChoice -> ClassifiedPrompt.ModalChoice(p)
            PromptSemantic.Search -> ClassifiedPrompt.Search(p)
            PromptSemantic.OrderForBottom,
            PromptSemantic.OrderForTop,
            -> ClassifiedPrompt.Order(p)
            PromptSemantic.OrderGeneric -> ClassifiedPrompt.AutoResolve(p)
            PromptSemantic.SelectNLegendRule,
            PromptSemantic.SelectNDiscard,
            PromptSemantic.RevealChoose,
            PromptSemantic.SelectNSacrificeEffect,
            PromptSemantic.SelectNCostSacrifice,
            PromptSemantic.SelectNCostExileFromGrave,
            PromptSemantic.SelectNCostCollectEvidence,
            PromptSemantic.EnlistCost,
            PromptSemantic.StationTapCost,
            PromptSemantic.ReturnUnblockedAttackerCost,
            PromptSemantic.WaterbendCost,
            PromptSemantic.SelectNResolution,
            PromptSemantic.SelectNLibraryPutback,
            PromptSemantic.MutateTopBottom,
            PromptSemantic.LearnLesson,
            PromptSemantic.StaticColorChoice,
            PromptSemantic.StaticSubtypeChoice,
            PromptSemantic.StaticParityChoice,
            PromptSemantic.Generic,
            -> null
        }
    }

    private fun selectNReason(semantic: PromptSemantic): SelectNReason? = staticChoiceReason(semantic) ?: selectNReasonBySemantic(semantic)

    private fun staticChoiceReason(semantic: PromptSemantic): SelectNReason? =
        when (semantic) {
            PromptSemantic.StaticColorChoice -> SelectNReason.StaticColorChoice
            PromptSemantic.StaticSubtypeChoice -> SelectNReason.StaticSubtypeChoice
            PromptSemantic.StaticParityChoice -> SelectNReason.StaticParityChoice
            else -> null
        }

    private fun selectNReasonBySemantic(semantic: PromptSemantic): SelectNReason? =
        when (semantic) {
            PromptSemantic.SelectNLegendRule -> SelectNReason.LegendRule
            PromptSemantic.SelectNDiscard -> SelectNReason.Discard
            PromptSemantic.RevealChoose -> SelectNReason.RevealChoose
            PromptSemantic.SelectNSacrificeEffect -> SelectNReason.SacrificeEffect
            PromptSemantic.SelectNCostSacrifice -> SelectNReason.Sacrifice
            PromptSemantic.SelectNCostExileFromGrave -> SelectNReason.ExileFromGrave
            PromptSemantic.SelectNCostCollectEvidence -> SelectNReason.CollectEvidenceCost
            PromptSemantic.EnlistCost -> SelectNReason.EnlistCost
            PromptSemantic.StationTapCost -> SelectNReason.StationTapCost
            PromptSemantic.ReturnUnblockedAttackerCost -> SelectNReason.ReturnUnblockedAttackerCost
            PromptSemantic.WaterbendCost -> SelectNReason.WaterbendCost
            PromptSemantic.SelectNResolution -> SelectNReason.Resolution
            PromptSemantic.SelectNLibraryPutback -> SelectNReason.LibraryPutback
            PromptSemantic.MutateTopBottom -> SelectNReason.MutateTopBottom
            PromptSemantic.LearnLesson -> SelectNReason.LearnLesson
            PromptSemantic.GroupingSurveil,
            PromptSemantic.GroupingScry,
            PromptSemantic.ModalChoice,
            PromptSemantic.Search,
            PromptSemantic.OrderForBottom,
            PromptSemantic.OrderForTop,
            PromptSemantic.OrderGeneric,
            PromptSemantic.StaticColorChoice,
            PromptSemantic.StaticSubtypeChoice,
            PromptSemantic.StaticParityChoice,
            PromptSemantic.Generic,
            -> null
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
