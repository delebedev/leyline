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
        val reason: Reason,
    ) : ClassifiedPrompt {
        enum class Reason {
            LegendRule,
            Discard,
            Sacrifice,
            SacrificeEffect,
            RevealChoose,
            Resolution,
            LibraryPutback,
            ExileFromGrave,
            CollectEvidenceCost,
            EnlistCost,
            StationTapCost,
            ReturnUnblockedAttackerCost,
            WaterbendCost,
            MutateTopBottom,
            LearnLesson,
            StaticColorChoice,
            StaticSubtypeChoice,
        }
    }

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
            PromptSemantic.Generic,
            -> null
        }
    }

    private fun selectNReason(semantic: PromptSemantic): ClassifiedPrompt.SelectN.Reason? =
        when (semantic) {
            PromptSemantic.SelectNLegendRule -> ClassifiedPrompt.SelectN.Reason.LegendRule
            PromptSemantic.SelectNDiscard -> ClassifiedPrompt.SelectN.Reason.Discard
            PromptSemantic.RevealChoose -> ClassifiedPrompt.SelectN.Reason.RevealChoose
            PromptSemantic.SelectNSacrificeEffect -> ClassifiedPrompt.SelectN.Reason.SacrificeEffect
            PromptSemantic.SelectNCostSacrifice -> ClassifiedPrompt.SelectN.Reason.Sacrifice
            PromptSemantic.SelectNCostExileFromGrave -> ClassifiedPrompt.SelectN.Reason.ExileFromGrave
            PromptSemantic.SelectNCostCollectEvidence -> ClassifiedPrompt.SelectN.Reason.CollectEvidenceCost
            PromptSemantic.EnlistCost -> ClassifiedPrompt.SelectN.Reason.EnlistCost
            PromptSemantic.StationTapCost -> ClassifiedPrompt.SelectN.Reason.StationTapCost
            PromptSemantic.ReturnUnblockedAttackerCost -> ClassifiedPrompt.SelectN.Reason.ReturnUnblockedAttackerCost
            PromptSemantic.WaterbendCost -> ClassifiedPrompt.SelectN.Reason.WaterbendCost
            PromptSemantic.SelectNResolution -> ClassifiedPrompt.SelectN.Reason.Resolution
            PromptSemantic.SelectNLibraryPutback -> ClassifiedPrompt.SelectN.Reason.LibraryPutback
            PromptSemantic.MutateTopBottom -> ClassifiedPrompt.SelectN.Reason.MutateTopBottom
            PromptSemantic.LearnLesson -> ClassifiedPrompt.SelectN.Reason.LearnLesson
            PromptSemantic.StaticColorChoice -> ClassifiedPrompt.SelectN.Reason.StaticColorChoice
            PromptSemantic.StaticSubtypeChoice -> ClassifiedPrompt.SelectN.Reason.StaticSubtypeChoice
            PromptSemantic.GroupingSurveil,
            PromptSemantic.GroupingScry,
            PromptSemantic.ModalChoice,
            PromptSemantic.Search,
            PromptSemantic.OrderForBottom,
            PromptSemantic.OrderForTop,
            PromptSemantic.OrderGeneric,
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
