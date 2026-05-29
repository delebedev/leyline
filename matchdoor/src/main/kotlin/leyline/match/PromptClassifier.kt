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
            ExileFromGrave,
            CollectEvidenceCost,
            EnlistCost,
            StationTapCost,
            ReturnUnblockedAttackerCost,
            MutateTopBottom,
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
        return classifyBySemantic(pendingPrompt, req) ?: classifyGeneric(pendingPrompt, req)
    }

    // Exhaustive on PromptSemantic — adding a new variant fails compile until classified.
    private fun classifyBySemantic(
        p: InteractivePromptBridge.PendingPrompt,
        req: PromptRequest,
    ): ClassifiedPrompt? =
        when (req.semantic) {
            PromptSemantic.GroupingSurveil -> ClassifiedPrompt.Grouping(p, GroupingContext.Surveil)
            PromptSemantic.GroupingScry -> ClassifiedPrompt.Grouping(p, GroupingContext.Scry_a0f6)
            PromptSemantic.ModalChoice -> ClassifiedPrompt.ModalChoice(p)
            PromptSemantic.SelectNLegendRule ->
                ClassifiedPrompt.SelectN(p, ClassifiedPrompt.SelectN.Reason.LegendRule)
            PromptSemantic.SelectNDiscard ->
                ClassifiedPrompt.SelectN(p, ClassifiedPrompt.SelectN.Reason.Discard)
            PromptSemantic.Search -> ClassifiedPrompt.Search(p)
            PromptSemantic.RevealChoose ->
                ClassifiedPrompt.SelectN(p, ClassifiedPrompt.SelectN.Reason.RevealChoose)
            PromptSemantic.SelectNSacrificeEffect ->
                ClassifiedPrompt.SelectN(p, ClassifiedPrompt.SelectN.Reason.SacrificeEffect)
            PromptSemantic.SelectNCostSacrifice ->
                ClassifiedPrompt.SelectN(p, ClassifiedPrompt.SelectN.Reason.Sacrifice)
            PromptSemantic.SelectNCostExileFromGrave ->
                ClassifiedPrompt.SelectN(p, ClassifiedPrompt.SelectN.Reason.ExileFromGrave)
            PromptSemantic.SelectNCostCollectEvidence ->
                ClassifiedPrompt.SelectN(p, ClassifiedPrompt.SelectN.Reason.CollectEvidenceCost)
            PromptSemantic.EnlistCost ->
                ClassifiedPrompt.SelectN(p, ClassifiedPrompt.SelectN.Reason.EnlistCost)
            PromptSemantic.StationTapCost ->
                ClassifiedPrompt.SelectN(p, ClassifiedPrompt.SelectN.Reason.StationTapCost)
            PromptSemantic.ReturnUnblockedAttackerCost ->
                ClassifiedPrompt.SelectN(p, ClassifiedPrompt.SelectN.Reason.ReturnUnblockedAttackerCost)
            PromptSemantic.SelectNResolution ->
                ClassifiedPrompt.SelectN(p, ClassifiedPrompt.SelectN.Reason.Resolution)
            PromptSemantic.MutateTopBottom ->
                ClassifiedPrompt.SelectN(p, ClassifiedPrompt.SelectN.Reason.MutateTopBottom)
            PromptSemantic.Generic -> null
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
