package leyline.bridge.handoff

import leyline.bridge.types.PromptCandidateKind
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType

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

    /** Dynamic resolution choice outside the characterized hidden-library card shape. */
    data class ResolutionResidual(
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

    /** Static enum SelectN semantics owned by one value-only runtime. */
    data class StaticChoice(
        val descriptor: StaticChoicePromptRoute,
    ) : ResolvedPromptRoute {
        override val semantic: PromptSemantic = descriptor.semantic
    }

    /** Reveal-backed SelectN choice owned by its exact journal entry. */
    data class RevealChoice(
        override val semantic: PromptSemantic,
    ) : ResolvedPromptRoute

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
    LegendRule,
    LibraryPutback,
    ManifestDread,
    Discard,
    SacrificeEffect,
    Suspect,
    MutateTopBottom,
    Resolution,
    Learn,
}

enum class ResolutionAbilityShape {
    Dig,
    Other,
}

/** Candidate-domain facts fixed by the engine callback before route binding. */
data class ResolutionRouteInput(
    val optionCount: Int,
    val candidateCount: Int,
    val candidateKinds: Set<PromptCandidateKind>,
    val candidateZones: Set<String?>,
    val abilityShape: ResolutionAbilityShape,
) {
    val isCompleteLibraryCardChoice: Boolean
        get() =
            candidateCount == optionCount &&
                candidateKinds == setOf(PromptCandidateKind.Card) &&
                candidateZones == setOf("Library")

    val isHiddenLibraryCardChoice: Boolean
        get() = isCompleteLibraryCardChoice && abilityShape == ResolutionAbilityShape.Dig
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

enum class StaticChoiceKind {
    Color,
    Subtype,
    Parity,
}

data class StaticChoicePromptRoute(
    val semantic: PromptSemantic,
    val kind: StaticChoiceKind,
)

data class SelectNPromptRoute(
    val semantic: PromptSemantic,
    val shape: SelectNShape,
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
    private val dynamicResolutionShape =
        SelectNShape(
            SelectionContext.Resolution_a163,
            SelectionListType.Dynamic,
            OptionContext.Resolution_a9d7,
        )

    @Suppress("CyclomaticComplexMethod") // Exhaustive PromptSemantic catalog is the single route authority.
    fun resolve(
        semantic: PromptSemantic,
        hasCandidateRefs: Boolean = false,
        resolutionInput: ResolutionRouteInput? = null,
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
            PromptSemantic.SelectNLegendRule -> cardSelect(semantic, CardSelectKind.LegendRule)
            PromptSemantic.SelectNDiscard -> cardSelect(semantic, CardSelectKind.Discard, choiceResultSentiment = 1)
            PromptSemantic.RevealChoose -> ResolvedPromptRoute.RevealChoice(semantic)
            PromptSemantic.SelectNResolution ->
                if (resolutionInput?.isHiddenLibraryCardChoice == true) {
                    cardSelect(semantic, CardSelectKind.Resolution)
                } else {
                    resolutionResidual(semantic, dynamicResolutionShape)
                }
            PromptSemantic.ManifestDread -> cardSelect(semantic, CardSelectKind.ManifestDread)
            PromptSemantic.SuspectChoice -> cardSelect(semantic, CardSelectKind.Suspect, choiceResultSentiment = 2)
            PromptSemantic.SelectNLibraryPutback -> cardSelect(semantic, CardSelectKind.LibraryPutback)
            PromptSemantic.SelectNSacrificeEffect ->
                cardSelect(semantic, CardSelectKind.SacrificeEffect, choiceResultSentiment = 1)
            PromptSemantic.MutateTopBottom -> cardSelect(semantic, CardSelectKind.MutateTopBottom)
            PromptSemantic.LearnLesson -> cardSelect(semantic, CardSelectKind.Learn)
            PromptSemantic.StaticColorChoice ->
                staticChoice(semantic, StaticChoiceKind.Color)
            PromptSemantic.StaticSubtypeChoice ->
                staticChoice(semantic, StaticChoiceKind.Subtype)
            PromptSemantic.StaticParityChoice ->
                staticChoice(semantic, StaticChoiceKind.Parity)
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

    private fun resolutionResidual(
        semantic: PromptSemantic,
        shape: SelectNShape,
    ): ResolvedPromptRoute.ResolutionResidual =
        ResolvedPromptRoute.ResolutionResidual(
            SelectNPromptRoute(semantic, shape),
        )

    private fun cardSelect(
        semantic: PromptSemantic,
        kind: CardSelectKind,
        choiceResultSentiment: Int? = null,
    ): ResolvedPromptRoute.CardSelect =
        ResolvedPromptRoute.CardSelect(CardSelectPromptRoute(semantic, kind, choiceResultSentiment = choiceResultSentiment))

    private fun staticChoice(
        semantic: PromptSemantic,
        kind: StaticChoiceKind,
    ): ResolvedPromptRoute.StaticChoice = ResolvedPromptRoute.StaticChoice(StaticChoicePromptRoute(semantic, kind))

    private fun payCosts(
        semantic: PromptSemantic,
        kind: PayCostsRouteKind,
        templateLabel: String,
        manaSourcePayment: ManaSourcePaymentKind? = null,
    ): ResolvedPromptRoute.PayCosts = ResolvedPromptRoute.PayCosts(PayCostsPromptRoute(semantic, kind, templateLabel, manaSourcePayment))
}
