package leyline.game.bundle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.ManaSourcePaymentKind
import leyline.bridge.handoff.OrderRouteKind
import leyline.bridge.handoff.PayCostsPromptRoute
import leyline.bridge.handoff.PayCostsRouteKind
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.SelectNEnvelopeKind
import leyline.bridge.handoff.SelectNInnerPrompt
import leyline.bridge.handoff.SelectNPromptRoute
import leyline.bridge.handoff.SelectNShape
import leyline.bridge.handoff.StaticChoiceKind
import leyline.bridge.handoff.StaticChoicePolicy
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.StaticList

class PromptRouteMatrixTest :
    FunSpec({
        tags(UnitTag)

        test("every semantic has one characterized prompt route") {
            val expected =
                mapOf(
                    PromptSemantic.Generic to ResolvedPromptRoute.AutoResolve(PromptSemantic.Generic),
                    PromptSemantic.GroupingSurveil to
                        ResolvedPromptRoute.Grouping(PromptSemantic.GroupingSurveil, GroupingContext.Surveil),
                    PromptSemantic.GroupingScry to
                        ResolvedPromptRoute.Grouping(PromptSemantic.GroupingScry, GroupingContext.Scry_a0f6),
                    PromptSemantic.ModalChoice to ResolvedPromptRoute.ModalChoice(PromptSemantic.ModalChoice),
                    PromptSemantic.SelectNLegendRule to
                        selectN(PromptSemantic.SelectNLegendRule, SelectNInnerPrompt.LegendRule, SelectNEnvelopeKind.LegendRule),
                    PromptSemantic.SelectNDiscard to
                        selectN(
                            PromptSemantic.SelectNDiscard,
                            SelectNInnerPrompt.DiscardCost,
                            SelectNEnvelopeKind.Default,
                            shape = discardShape,
                            sentiment = 1,
                        ),
                    PromptSemantic.Search to ResolvedPromptRoute.Search(PromptSemantic.Search),
                    PromptSemantic.OrderForBottom to
                        ResolvedPromptRoute.Order(PromptSemantic.OrderForBottom, OrderRouteKind.Bottom),
                    PromptSemantic.OrderForTop to ResolvedPromptRoute.Order(PromptSemantic.OrderForTop, OrderRouteKind.Top),
                    PromptSemantic.OrderGeneric to ResolvedPromptRoute.AutoResolve(PromptSemantic.OrderGeneric),
                    PromptSemantic.RevealChoose to
                        selectN(PromptSemantic.RevealChoose, SelectNInnerPrompt.GenericSelectN, SelectNEnvelopeKind.RevealChoose),
                    PromptSemantic.SelectNResolution to
                        selectN(
                            PromptSemantic.SelectNResolution,
                            SelectNInnerPrompt.SelectNInnerParameter,
                            SelectNEnvelopeKind.Resolution,
                        ),
                    PromptSemantic.SuspectChoice to
                        selectN(
                            PromptSemantic.SuspectChoice,
                            SelectNInnerPrompt.SelectNInnerParameter,
                            SelectNEnvelopeKind.SuspectChoice,
                            sentiment = 2,
                        ),
                    PromptSemantic.SelectNLibraryPutback to
                        selectN(
                            PromptSemantic.SelectNLibraryPutback,
                            SelectNInnerPrompt.SelectNInnerParameter,
                            SelectNEnvelopeKind.LibraryPutback,
                        ),
                    PromptSemantic.SelectNSacrificeEffect to
                        selectN(
                            PromptSemantic.SelectNSacrificeEffect,
                            SelectNInnerPrompt.GenericSelectN,
                            SelectNEnvelopeKind.Default,
                            sentiment = 1,
                        ),
                    PromptSemantic.SelectNCostSacrifice to
                        payCosts(PromptSemantic.SelectNCostSacrifice, PayCostsRouteKind.Sacrifice, "sacrifice"),
                    PromptSemantic.SelectNCostExileFromGrave to
                        payCosts(
                            PromptSemantic.SelectNCostExileFromGrave,
                            PayCostsRouteKind.SelectCostExileFromGrave,
                            "exile-from-grave",
                        ),
                    PromptSemantic.SelectNCostCollectEvidence to
                        payCosts(PromptSemantic.SelectNCostCollectEvidence, PayCostsRouteKind.CollectEvidence, "collect-evidence"),
                    PromptSemantic.EnlistCost to payCosts(PromptSemantic.EnlistCost, PayCostsRouteKind.EnlistCost, "enlist"),
                    PromptSemantic.TeamworkCost to payCosts(PromptSemantic.TeamworkCost, PayCostsRouteKind.TeamworkCost, "teamwork"),
                    PromptSemantic.StationTapCost to payCosts(PromptSemantic.StationTapCost, PayCostsRouteKind.StationTapCost, "station"),
                    PromptSemantic.ReturnUnblockedAttackerCost to
                        payCosts(
                            PromptSemantic.ReturnUnblockedAttackerCost,
                            PayCostsRouteKind.SelectCostReturnAttacker,
                            "return-unblocked-attacker",
                        ),
                    PromptSemantic.ConvokeCost to
                        payCosts(
                            PromptSemantic.ConvokeCost,
                            PayCostsRouteKind.ConvokeCost,
                            "convoke",
                            ManaSourcePaymentKind.Convoke,
                        ),
                    PromptSemantic.ImproviseCost to
                        payCosts(
                            PromptSemantic.ImproviseCost,
                            PayCostsRouteKind.ImproviseCost,
                            "improvise",
                            ManaSourcePaymentKind.Improvise,
                        ),
                    PromptSemantic.WaterbendCost to
                        payCosts(
                            PromptSemantic.WaterbendCost,
                            PayCostsRouteKind.WaterbendCost,
                            "waterbend",
                            ManaSourcePaymentKind.Waterbend,
                        ),
                    PromptSemantic.MutateTopBottom to
                        selectN(
                            PromptSemantic.MutateTopBottom,
                            SelectNInnerPrompt.GenericSelectN,
                            SelectNEnvelopeKind.MutateTopBottom,
                        ),
                    PromptSemantic.LearnLesson to
                        selectN(PromptSemantic.LearnLesson, SelectNInnerPrompt.LearnInnerParameter, SelectNEnvelopeKind.LearnLesson),
                    PromptSemantic.StaticColorChoice to
                        staticChoice(PromptSemantic.StaticColorChoice, StaticChoiceKind.Color, 6, staticShape),
                    PromptSemantic.StaticSubtypeChoice to
                        staticChoice(PromptSemantic.StaticSubtypeChoice, StaticChoiceKind.Subtype, 5, staticSubsetShape),
                    PromptSemantic.StaticParityChoice to
                        staticChoice(
                            PromptSemantic.StaticParityChoice,
                            StaticChoiceKind.Parity,
                            StaticList.Parities.number,
                            staticShape,
                        ),
                )

            PromptSemantic.entries
                .associateWith { PromptRouteResolver.resolve(it) }
                .shouldContainExactly(expected)
        }

        test("Generic fallback resolves once from candidate presence") {
            listOf(
                PromptRouteResolver.resolve(PromptSemantic.Generic, hasCandidateRefs = true)::class,
                PromptRouteResolver.resolve(PromptSemantic.Generic, hasCandidateRefs = false)::class,
            ) shouldBe
                listOf(
                    ResolvedPromptRoute.Targeting::class,
                    ResolvedPromptRoute.AutoResolve::class,
                )
        }
    })

private val dynamicShape =
    SelectNShape(SelectionContext.Resolution_a163, SelectionListType.Dynamic, OptionContext.Resolution_a9d7)
private val staticShape = dynamicShape.copy(listType = SelectionListType.Static)
private val staticSubsetShape = dynamicShape.copy(listType = SelectionListType.StaticSubset)
private val discardShape = SelectNShape(SelectionContext.Discard_a163, SelectionListType.Static, OptionContext.Payment)

private fun selectN(
    semantic: PromptSemantic,
    innerPrompt: SelectNInnerPrompt,
    envelopeKind: SelectNEnvelopeKind,
    shape: SelectNShape = dynamicShape,
    sentiment: Int? = null,
): ResolvedPromptRoute.SelectN =
    ResolvedPromptRoute.SelectN(SelectNPromptRoute(semantic, shape, innerPrompt, envelopeKind, choiceResultSentiment = sentiment))

private fun staticChoice(
    semantic: PromptSemantic,
    kind: StaticChoiceKind,
    choiceDomain: Int,
    shape: SelectNShape,
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
