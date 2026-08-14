package leyline.bridge.handoff

import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.StaticList

/**
 * Immutable protocol route fixed when a [PromptRequest] is created.
 *
 * The route is the sole prompt-family authority for the pending interaction.
 * Match lifecycle handlers and request builders consume it without
 * reclassifying [semantic].
 */
sealed interface ResolvedPromptRoute {
    val semantic: PromptSemantic

    data class Grouping(
        override val semantic: PromptSemantic,
        val context: GroupingContext,
    ) : ResolvedPromptRoute

    data class ModalChoice(
        override val semantic: PromptSemantic,
    ) : ResolvedPromptRoute

    data class SelectN(
        val descriptor: SelectNPromptRoute,
    ) : ResolvedPromptRoute {
        override val semantic: PromptSemantic = descriptor.semantic
    }

    /** Card-backed SelectN semantics owned by one exact-handle runtime. */
    data class CardSelect(
        val descriptor: CardSelectPromptRoute,
    ) : ResolvedPromptRoute {
        override val semantic: PromptSemantic = descriptor.semantic
    }

    data class PayCosts(
        val descriptor: PayCostsPromptRoute,
    ) : ResolvedPromptRoute {
        override val semantic: PromptSemantic = descriptor.semantic
    }

    data class Search(
        override val semantic: PromptSemantic,
    ) : ResolvedPromptRoute

    data class Order(
        override val semantic: PromptSemantic,
        val kind: OrderRouteKind,
    ) : ResolvedPromptRoute

    data class Targeting(
        override val semantic: PromptSemantic,
    ) : ResolvedPromptRoute

    /**
     * Candidate-backed Generic prompt whose response family is not yet
     * classified. This route remains on [InteractivePromptBridge].
     */
    data class UnclassifiedCandidate(
        override val semantic: PromptSemantic,
    ) : ResolvedPromptRoute

    data class AutoResolve(
        override val semantic: PromptSemantic,
    ) : ResolvedPromptRoute
}

enum class OrderRouteKind {
    Bottom,
    Top,
}

enum class CardSelectKind {
    Discard,
    SacrificeEffect,
    Suspect,
    MutateTopBottom,
}

data class CardSelectPromptRoute(
    val semantic: PromptSemantic,
    val kind: CardSelectKind,
    val choiceResultSentiment: Int? = null,
)

data class SelectNShape(
    val context: SelectionContext,
    val listType: SelectionListType,
    val optionContext: OptionContext,
)

enum class SelectNInnerPrompt {
    StaticChoice,
    LegendRule,
    GenericSelectN,
    SelectNInnerParameter,
    ManifestDreadInnerParameter,
    LearnInnerParameter,
}

enum class SelectNEnvelopeKind {
    Default,
    LegendRule,
    RevealChoose,
    Resolution,
    ManifestDread,
    LibraryPutback,
    LearnLesson,
    StaticChoice,
}

enum class StaticChoiceKind {
    Color,
    Subtype,
    Parity,
}

data class StaticChoicePolicy(
    val kind: StaticChoiceKind,
    val choiceDomain: Int,
)

data class SelectNPromptRoute(
    val semantic: PromptSemantic,
    val shape: SelectNShape,
    val innerPrompt: SelectNInnerPrompt,
    val envelopeKind: SelectNEnvelopeKind,
    val staticChoice: StaticChoicePolicy? = null,
    val choiceResultSentiment: Int? = null,
)

enum class PayCostsRouteKind {
    Sacrifice,
    SelectCostExileFromGrave,
    SelectCostReturnAttacker,
    CollectEvidence,
    StationTapCost,
    EnlistCost,
    TeamworkCost,
    ConvokeCost,
    ImproviseCost,
    WaterbendCost,
}

enum class ManaSourcePaymentKind {
    Convoke,
    Improvise,
    Waterbend,
}

data class PayCostsPromptRoute(
    val semantic: PromptSemantic,
    val kind: PayCostsRouteKind,
    val templateLabel: String,
    val manaSourcePayment: ManaSourcePaymentKind? = null,
)

object PromptRouteResolver {
    private val staticResolutionShape =
        SelectNShape(
            SelectionContext.Resolution_a163,
            SelectionListType.Static,
            OptionContext.Resolution_a9d7,
        )
    private val dynamicResolutionShape = staticResolutionShape.copy(listType = SelectionListType.Dynamic)

    @Suppress("CyclomaticComplexMethod") // Exhaustive PromptSemantic catalog is the single route authority.
    fun resolve(
        semantic: PromptSemantic,
        hasCandidateRefs: Boolean = false,
    ): ResolvedPromptRoute =
        when (semantic) {
            PromptSemantic.Generic ->
                if (hasCandidateRefs) {
                    ResolvedPromptRoute.UnclassifiedCandidate(semantic)
                } else {
                    ResolvedPromptRoute.AutoResolve(semantic)
                }
            PromptSemantic.TargetSelection -> ResolvedPromptRoute.Targeting(semantic)
            PromptSemantic.GroupingSurveil -> ResolvedPromptRoute.Grouping(semantic, GroupingContext.Surveil)
            PromptSemantic.GroupingScry -> ResolvedPromptRoute.Grouping(semantic, GroupingContext.Scry_a0f6)
            PromptSemantic.ModalChoice -> ResolvedPromptRoute.ModalChoice(semantic)
            PromptSemantic.Search -> ResolvedPromptRoute.Search(semantic)
            PromptSemantic.OrderForBottom -> ResolvedPromptRoute.Order(semantic, OrderRouteKind.Bottom)
            PromptSemantic.OrderForTop -> ResolvedPromptRoute.Order(semantic, OrderRouteKind.Top)
            PromptSemantic.OrderGeneric -> ResolvedPromptRoute.AutoResolve(semantic)
            PromptSemantic.SelectNLegendRule ->
                selectN(semantic, dynamicResolutionShape, SelectNInnerPrompt.LegendRule, SelectNEnvelopeKind.LegendRule)
            PromptSemantic.SelectNDiscard -> cardSelect(semantic, CardSelectKind.Discard, choiceResultSentiment = 1)
            PromptSemantic.RevealChoose ->
                selectN(semantic, dynamicResolutionShape, SelectNInnerPrompt.GenericSelectN, SelectNEnvelopeKind.RevealChoose)
            PromptSemantic.SelectNResolution ->
                selectN(semantic, dynamicResolutionShape, SelectNInnerPrompt.SelectNInnerParameter, SelectNEnvelopeKind.Resolution)
            PromptSemantic.ManifestDread ->
                selectN(
                    semantic,
                    dynamicResolutionShape,
                    SelectNInnerPrompt.ManifestDreadInnerParameter,
                    SelectNEnvelopeKind.ManifestDread,
                )
            PromptSemantic.SuspectChoice -> cardSelect(semantic, CardSelectKind.Suspect, choiceResultSentiment = 2)
            PromptSemantic.SelectNLibraryPutback ->
                selectN(semantic, dynamicResolutionShape, SelectNInnerPrompt.SelectNInnerParameter, SelectNEnvelopeKind.LibraryPutback)
            PromptSemantic.SelectNSacrificeEffect ->
                cardSelect(semantic, CardSelectKind.SacrificeEffect, choiceResultSentiment = 1)
            PromptSemantic.MutateTopBottom -> cardSelect(semantic, CardSelectKind.MutateTopBottom)
            PromptSemantic.LearnLesson ->
                selectN(semantic, dynamicResolutionShape, SelectNInnerPrompt.LearnInnerParameter, SelectNEnvelopeKind.LearnLesson)
            PromptSemantic.StaticColorChoice ->
                staticChoice(semantic, staticResolutionShape, StaticChoiceKind.Color, 6)
            PromptSemantic.StaticSubtypeChoice ->
                staticChoice(
                    semantic,
                    staticResolutionShape.copy(listType = SelectionListType.StaticSubset),
                    StaticChoiceKind.Subtype,
                    5,
                )
            PromptSemantic.StaticParityChoice ->
                staticChoice(semantic, staticResolutionShape, StaticChoiceKind.Parity, StaticList.Parities.number)
            PromptSemantic.SelectNCostSacrifice -> payCosts(semantic, PayCostsRouteKind.Sacrifice, "sacrifice")
            PromptSemantic.SelectNCostExileFromGrave ->
                payCosts(semantic, PayCostsRouteKind.SelectCostExileFromGrave, "exile-from-grave")
            PromptSemantic.SelectNCostCollectEvidence ->
                payCosts(semantic, PayCostsRouteKind.CollectEvidence, "collect-evidence")
            PromptSemantic.EnlistCost -> payCosts(semantic, PayCostsRouteKind.EnlistCost, "enlist")
            PromptSemantic.TeamworkCost -> payCosts(semantic, PayCostsRouteKind.TeamworkCost, "teamwork")
            PromptSemantic.StationTapCost -> payCosts(semantic, PayCostsRouteKind.StationTapCost, "station")
            PromptSemantic.ReturnUnblockedAttackerCost ->
                payCosts(semantic, PayCostsRouteKind.SelectCostReturnAttacker, "return-unblocked-attacker")
            PromptSemantic.ConvokeCost ->
                payCosts(semantic, PayCostsRouteKind.ConvokeCost, "convoke", ManaSourcePaymentKind.Convoke)
            PromptSemantic.ImproviseCost ->
                payCosts(semantic, PayCostsRouteKind.ImproviseCost, "improvise", ManaSourcePaymentKind.Improvise)
            PromptSemantic.WaterbendCost ->
                payCosts(semantic, PayCostsRouteKind.WaterbendCost, "waterbend", ManaSourcePaymentKind.Waterbend)
        }

    private fun selectN(
        semantic: PromptSemantic,
        shape: SelectNShape,
        innerPrompt: SelectNInnerPrompt,
        envelopeKind: SelectNEnvelopeKind,
        choiceResultSentiment: Int? = null,
    ): ResolvedPromptRoute.SelectN =
        ResolvedPromptRoute.SelectN(
            SelectNPromptRoute(semantic, shape, innerPrompt, envelopeKind, choiceResultSentiment = choiceResultSentiment),
        )

    private fun cardSelect(
        semantic: PromptSemantic,
        kind: CardSelectKind,
        choiceResultSentiment: Int? = null,
    ): ResolvedPromptRoute.CardSelect =
        ResolvedPromptRoute.CardSelect(CardSelectPromptRoute(semantic, kind, choiceResultSentiment = choiceResultSentiment))

    private fun staticChoice(
        semantic: PromptSemantic,
        shape: SelectNShape,
        kind: StaticChoiceKind,
        choiceDomain: Int,
    ): ResolvedPromptRoute.SelectN =
        ResolvedPromptRoute.SelectN(
            SelectNPromptRoute(
                semantic,
                shape,
                SelectNInnerPrompt.StaticChoice,
                SelectNEnvelopeKind.StaticChoice,
                StaticChoicePolicy(kind, choiceDomain),
                choiceResultSentiment = 2,
            ),
        )

    private fun payCosts(
        semantic: PromptSemantic,
        kind: PayCostsRouteKind,
        templateLabel: String,
        manaSourcePayment: ManaSourcePaymentKind? = null,
    ): ResolvedPromptRoute.PayCosts = ResolvedPromptRoute.PayCosts(PayCostsPromptRoute(semantic, kind, templateLabel, manaSourcePayment))
}
