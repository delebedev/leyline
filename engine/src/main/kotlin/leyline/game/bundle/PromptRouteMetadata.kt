package leyline.game.bundle

import leyline.bridge.handoff.PromptSemantic
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext

enum class PromptRouteFamily(
    val expectedGreFamily: String?,
) {
    Grouping("GroupReq"),
    ModalChoice("CastingTimeOptionsReq"),
    SelectN("SelectNReq"),
    PayCosts("PayCostsReq"),
    Search("SearchReq"),
    Order("OrderReq"),
    AutoResolve(null),
}

data class PromptRouteMetadata(
    val family: PromptRouteFamily,
    val groupingContext: GroupingContext? = null,
)

object PromptSemanticRouteMetadata {
    // Exhaustive on PromptSemantic - adding a new variant fails compile until classified.
    fun route(semantic: PromptSemantic): PromptRouteMetadata? =
        when (semantic) {
            PromptSemantic.GroupingSurveil -> PromptRouteMetadata(PromptRouteFamily.Grouping, GroupingContext.Surveil)
            PromptSemantic.GroupingScry -> PromptRouteMetadata(PromptRouteFamily.Grouping, GroupingContext.Scry_a0f6)
            PromptSemantic.ModalChoice -> PromptRouteMetadata(PromptRouteFamily.ModalChoice)
            PromptSemantic.Search -> PromptRouteMetadata(PromptRouteFamily.Search)
            PromptSemantic.OrderForBottom,
            PromptSemantic.OrderForTop,
            -> PromptRouteMetadata(PromptRouteFamily.Order)
            PromptSemantic.OrderGeneric -> PromptRouteMetadata(PromptRouteFamily.AutoResolve)
            PromptSemantic.SelectNLegendRule,
            PromptSemantic.SelectNDiscard,
            PromptSemantic.RevealChoose,
            PromptSemantic.SelectNResolution,
            PromptSemantic.SuspectChoice,
            PromptSemantic.SelectNLibraryPutback,
            PromptSemantic.SelectNSacrificeEffect,
            PromptSemantic.MutateTopBottom,
            PromptSemantic.LearnLesson,
            PromptSemantic.StaticColorChoice,
            PromptSemantic.StaticSubtypeChoice,
            PromptSemantic.StaticParityChoice,
            -> PromptRouteMetadata(PromptRouteFamily.SelectN)
            PromptSemantic.SelectNCostSacrifice,
            PromptSemantic.SelectNCostExileFromGrave,
            PromptSemantic.SelectNCostCollectEvidence,
            PromptSemantic.EnlistCost,
            PromptSemantic.TeamworkCost,
            PromptSemantic.StationTapCost,
            PromptSemantic.ReturnUnblockedAttackerCost,
            PromptSemantic.ConvokeCost,
            PromptSemantic.ImproviseCost,
            PromptSemantic.WaterbendCost,
            -> PromptRouteMetadata(PromptRouteFamily.PayCosts)
            PromptSemantic.Generic -> null
        }
}
