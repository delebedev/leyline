package leyline.game.bundle

import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptSemantic
import leyline.game.mapping.PromptIds
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.PayCostsReq
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.StaticList

internal data class SelectNShape(
    val context: SelectionContext,
    val listType: SelectionListType,
    val optionContext: OptionContext,
)

internal enum class SelectNInnerPrompt {
    StaticChoice,
    LegendRule,
    DiscardCost,
    GenericSelectN,
    SelectNInnerParameter,
    LearnInnerParameter,
}

internal enum class SelectNEnvelopeKind {
    Default,
    LegendRule,
    RevealChoose,
    Resolution,
    LibraryPutback,
    MutateTopBottom,
    LearnLesson,
}

internal sealed interface PromptRoute {
    val semantic: PromptSemantic
}

internal sealed interface SelectNPromptRoute : PromptRoute {
    val shape: SelectNShape
    val innerPrompt: SelectNInnerPrompt

    fun envelope(
        req: SelectNReq,
        learnPromptId: () -> Int,
    ): SelectNEnvelope
}

internal data class StandardSelectNRoute(
    override val semantic: PromptSemantic,
    override val shape: SelectNShape,
    override val innerPrompt: SelectNInnerPrompt,
    val envelopeKind: SelectNEnvelopeKind,
) : SelectNPromptRoute {
    override fun envelope(
        req: SelectNReq,
        learnPromptId: () -> Int,
    ): SelectNEnvelope =
        when (envelopeKind) {
            SelectNEnvelopeKind.Default -> SelectNEnvelope.default(req)
            SelectNEnvelopeKind.LegendRule -> SelectNEnvelope.legendRule(req)
            SelectNEnvelopeKind.RevealChoose -> SelectNEnvelope.revealChoose(req)
            SelectNEnvelopeKind.Resolution -> SelectNEnvelope.resolution(req)
            SelectNEnvelopeKind.LibraryPutback -> SelectNEnvelope.libraryPutback(req)
            SelectNEnvelopeKind.MutateTopBottom -> SelectNEnvelope.mutateTopBottom(req)
            SelectNEnvelopeKind.LearnLesson -> SelectNEnvelope.learnLesson(req, learnPromptId())
        }
}

internal data class StaticChoiceSelectNRoute(
    override val semantic: PromptSemantic,
    override val shape: SelectNShape,
    val outerPromptId: Int,
    val choiceDomain: Int,
) : SelectNPromptRoute {
    override val innerPrompt: SelectNInnerPrompt = SelectNInnerPrompt.StaticChoice

    override fun envelope(
        req: SelectNReq,
        learnPromptId: () -> Int,
    ): SelectNEnvelope = SelectNEnvelope.staticChoice(req, outerPromptId)
}

internal enum class PayCostsRouteKind {
    Sacrifice,
    SelectCost,
    CollectEvidence,
    StationTapCost,
    EnlistCost,
    WaterbendCost,
}

internal data class PayCostsPromptRoute(
    override val semantic: PromptSemantic,
    val kind: PayCostsRouteKind,
    val templateLabel: String,
    val promptId: Int? = null,
) : PromptRoute {
    fun build(
        prompt: InteractivePromptBridge.PendingPrompt,
        bridge: GameBridge,
    ): Pair<PayCostsReq, Prompt> =
        when (kind) {
            PayCostsRouteKind.Sacrifice -> RequestBuilder.buildSacrificePayCostsReq(prompt, bridge)
            PayCostsRouteKind.SelectCost ->
                RequestBuilder.buildSelectCostPayCostsReq(
                    prompt,
                    bridge,
                    promptId ?: error("missing SelectCost PayCosts prompt id for $semantic"),
                )
            PayCostsRouteKind.CollectEvidence -> CollectEvidencePayCostsBuilder.build(prompt, bridge)
            PayCostsRouteKind.StationTapCost -> RequestBuilder.buildStationTapCostPayCostsReq(prompt, bridge)
            PayCostsRouteKind.EnlistCost -> RequestBuilder.buildEnlistCostPayCostsReq(prompt, bridge)
            PayCostsRouteKind.WaterbendCost -> RequestBuilder.buildWaterbendCostPayCostsReq(prompt, bridge)
        }
}

internal fun SelectNPromptRoute.configureInnerPrompt(
    builder: SelectNReq.Builder,
    prompt: InteractivePromptBridge.PendingPrompt,
    bridge: GameBridge,
) {
    when (innerPrompt) {
        SelectNInnerPrompt.StaticChoice -> {
            builder.setSourceIdIfPresent(prompt, bridge)
            builder.setPrompt(Prompt.newBuilder())
        }
        SelectNInnerPrompt.LegendRule -> {
            builder.setPrompt(Prompt.newBuilder())
            builder.setSourceId(PromptIds.SELECT_N_LEGEND_RULE_SOURCE)
        }
        SelectNInnerPrompt.DiscardCost -> builder.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.DISCARD_COST))
        SelectNInnerPrompt.GenericSelectN -> {
            builder.setSourceIdIfPresent(prompt, bridge)
            builder.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.SELECT_N))
        }
        SelectNInnerPrompt.SelectNInnerParameter -> {
            builder.setSourceIdIfPresent(prompt, bridge)
            builder.setSelectNInnerPrompt(PromptIds.SELECT_N_INNER_PARAMETER)
        }
        SelectNInnerPrompt.LearnInnerParameter -> {
            builder.setSourceIdIfPresent(prompt, bridge)
            builder.setSelectNInnerPrompt(PromptIds.SELECT_N_LEARN_INNER_PARAMETER)
        }
    }
}

internal object SelectNPromptRoutes {
    private val staticResolutionShape =
        SelectNShape(
            SelectionContext.Resolution_a163,
            SelectionListType.Static,
            OptionContext.Resolution_a9d7,
        )

    private val dynamicResolutionShape = staticResolutionShape.copy(listType = SelectionListType.Dynamic)

    private val discardShape =
        SelectNShape(
            SelectionContext.Discard_a163,
            SelectionListType.Static,
            OptionContext.Payment,
        )

    private val selectNRoutesBySemantic =
        listOf(
            StaticChoiceSelectNRoute(
                semantic = PromptSemantic.StaticColorChoice,
                shape = staticResolutionShape,
                outerPromptId = PromptIds.CHOOSE_COLOR,
                choiceDomain = 6,
            ),
            StaticChoiceSelectNRoute(
                semantic = PromptSemantic.StaticSubtypeChoice,
                shape = staticResolutionShape.copy(listType = SelectionListType.StaticSubset),
                outerPromptId = PromptIds.CHOOSE_TYPE,
                choiceDomain = 5,
            ),
            StaticChoiceSelectNRoute(
                semantic = PromptSemantic.StaticParityChoice,
                shape = staticResolutionShape,
                outerPromptId = PromptIds.CHOOSE_TYPE,
                choiceDomain = StaticList.Parities.number,
            ),
            StandardSelectNRoute(
                semantic = PromptSemantic.SelectNLegendRule,
                shape = dynamicResolutionShape,
                innerPrompt = SelectNInnerPrompt.LegendRule,
                envelopeKind = SelectNEnvelopeKind.LegendRule,
            ),
            StandardSelectNRoute(
                semantic = PromptSemantic.SelectNDiscard,
                shape = discardShape,
                innerPrompt = SelectNInnerPrompt.DiscardCost,
                envelopeKind = SelectNEnvelopeKind.Default,
            ),
            StandardSelectNRoute(
                semantic = PromptSemantic.SelectNSacrificeEffect,
                shape = dynamicResolutionShape,
                innerPrompt = SelectNInnerPrompt.GenericSelectN,
                envelopeKind = SelectNEnvelopeKind.Default,
            ),
            StandardSelectNRoute(
                semantic = PromptSemantic.RevealChoose,
                shape = dynamicResolutionShape,
                innerPrompt = SelectNInnerPrompt.GenericSelectN,
                envelopeKind = SelectNEnvelopeKind.RevealChoose,
            ),
            StandardSelectNRoute(
                semantic = PromptSemantic.SelectNResolution,
                shape = dynamicResolutionShape,
                innerPrompt = SelectNInnerPrompt.SelectNInnerParameter,
                envelopeKind = SelectNEnvelopeKind.Resolution,
            ),
            StandardSelectNRoute(
                semantic = PromptSemantic.SelectNLibraryPutback,
                shape = dynamicResolutionShape,
                innerPrompt = SelectNInnerPrompt.SelectNInnerParameter,
                envelopeKind = SelectNEnvelopeKind.LibraryPutback,
            ),
            StandardSelectNRoute(
                semantic = PromptSemantic.MutateTopBottom,
                shape = dynamicResolutionShape,
                innerPrompt = SelectNInnerPrompt.GenericSelectN,
                envelopeKind = SelectNEnvelopeKind.MutateTopBottom,
            ),
            StandardSelectNRoute(
                semantic = PromptSemantic.LearnLesson,
                shape = dynamicResolutionShape,
                innerPrompt = SelectNInnerPrompt.LearnInnerParameter,
                envelopeKind = SelectNEnvelopeKind.LearnLesson,
            ),
        ).associateBy { it.semantic }

    private val payCostsRoutesBySemantic =
        listOf(
            PayCostsPromptRoute(
                semantic = PromptSemantic.SelectNCostSacrifice,
                kind = PayCostsRouteKind.Sacrifice,
                templateLabel = "sacrifice",
            ),
            PayCostsPromptRoute(
                semantic = PromptSemantic.SelectNCostExileFromGrave,
                kind = PayCostsRouteKind.SelectCost,
                templateLabel = "exile-from-grave",
                promptId = PromptIds.CHOOSE_OR_COST_PAY_EXILE_FROM_GRAVE,
            ),
            PayCostsPromptRoute(
                semantic = PromptSemantic.SelectNCostCollectEvidence,
                kind = PayCostsRouteKind.CollectEvidence,
                templateLabel = "collect-evidence",
            ),
            PayCostsPromptRoute(
                semantic = PromptSemantic.StationTapCost,
                kind = PayCostsRouteKind.StationTapCost,
                templateLabel = "station",
            ),
            PayCostsPromptRoute(
                semantic = PromptSemantic.ReturnUnblockedAttackerCost,
                kind = PayCostsRouteKind.SelectCost,
                templateLabel = "return-unblocked-attacker",
                promptId = PromptIds.NINJUTSU_RETURN_UNBLOCKED_ATTACKER_COST,
            ),
            PayCostsPromptRoute(
                semantic = PromptSemantic.WaterbendCost,
                kind = PayCostsRouteKind.WaterbendCost,
                templateLabel = "waterbend",
            ),
            PayCostsPromptRoute(
                semantic = PromptSemantic.EnlistCost,
                kind = PayCostsRouteKind.EnlistCost,
                templateLabel = "enlist",
            ),
        ).associateBy { it.semantic }

    fun route(semantic: PromptSemantic): SelectNPromptRoute? = selectNRoutesBySemantic[semantic]

    fun payCosts(semantic: PromptSemantic): PayCostsPromptRoute? = payCostsRoutesBySemantic[semantic]

    fun staticChoice(semantic: PromptSemantic): StaticChoiceSelectNRoute? = route(semantic) as? StaticChoiceSelectNRoute

    fun staticChoiceEnvelope(
        semantic: PromptSemantic,
        req: SelectNReq,
    ): SelectNEnvelope =
        SelectNEnvelope.staticChoice(
            req,
            staticChoice(semantic)?.outerPromptId ?: error("missing static choice route for $semantic"),
        )
}
