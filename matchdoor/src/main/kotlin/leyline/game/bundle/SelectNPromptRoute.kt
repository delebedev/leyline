package leyline.game.bundle

import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptSemantic
import leyline.game.mapping.PromptIds
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
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
    StaticChoice,
}

internal data class StaticChoiceRouteMetadata(
    val outerPromptId: Int,
    val choiceDomain: Int,
)

internal data class SelectNPromptRoute(
    val semantic: PromptSemantic,
    val shape: SelectNShape,
    val innerPrompt: SelectNInnerPrompt,
    val envelopeKind: SelectNEnvelopeKind,
    val staticChoice: StaticChoiceRouteMetadata? = null,
) {
    fun configureInnerPrompt(
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

    fun envelope(
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
            SelectNEnvelopeKind.StaticChoice ->
                SelectNEnvelope.staticChoice(
                    req,
                    staticChoice?.outerPromptId ?: error("missing static choice route metadata for $semantic"),
                )
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

    private val routesBySemantic =
        listOf(
            SelectNPromptRoute(
                semantic = PromptSemantic.StaticColorChoice,
                shape = staticResolutionShape,
                innerPrompt = SelectNInnerPrompt.StaticChoice,
                envelopeKind = SelectNEnvelopeKind.StaticChoice,
                staticChoice = StaticChoiceRouteMetadata(outerPromptId = PromptIds.CHOOSE_COLOR, choiceDomain = 6),
            ),
            SelectNPromptRoute(
                semantic = PromptSemantic.StaticSubtypeChoice,
                shape = staticResolutionShape.copy(listType = SelectionListType.StaticSubset),
                innerPrompt = SelectNInnerPrompt.StaticChoice,
                envelopeKind = SelectNEnvelopeKind.StaticChoice,
                staticChoice = StaticChoiceRouteMetadata(outerPromptId = PromptIds.CHOOSE_TYPE, choiceDomain = 5),
            ),
            SelectNPromptRoute(
                semantic = PromptSemantic.StaticParityChoice,
                shape = staticResolutionShape,
                innerPrompt = SelectNInnerPrompt.StaticChoice,
                envelopeKind = SelectNEnvelopeKind.StaticChoice,
                staticChoice = StaticChoiceRouteMetadata(outerPromptId = PromptIds.CHOOSE_TYPE, choiceDomain = StaticList.Parities.number),
            ),
            SelectNPromptRoute(
                semantic = PromptSemantic.SelectNLegendRule,
                shape = dynamicResolutionShape,
                innerPrompt = SelectNInnerPrompt.LegendRule,
                envelopeKind = SelectNEnvelopeKind.LegendRule,
            ),
            SelectNPromptRoute(
                semantic = PromptSemantic.SelectNDiscard,
                shape = discardShape,
                innerPrompt = SelectNInnerPrompt.DiscardCost,
                envelopeKind = SelectNEnvelopeKind.Default,
            ),
            SelectNPromptRoute(
                semantic = PromptSemantic.SelectNSacrificeEffect,
                shape = dynamicResolutionShape,
                innerPrompt = SelectNInnerPrompt.GenericSelectN,
                envelopeKind = SelectNEnvelopeKind.Default,
            ),
            SelectNPromptRoute(
                semantic = PromptSemantic.RevealChoose,
                shape = dynamicResolutionShape,
                innerPrompt = SelectNInnerPrompt.GenericSelectN,
                envelopeKind = SelectNEnvelopeKind.RevealChoose,
            ),
            SelectNPromptRoute(
                semantic = PromptSemantic.SelectNResolution,
                shape = dynamicResolutionShape,
                innerPrompt = SelectNInnerPrompt.SelectNInnerParameter,
                envelopeKind = SelectNEnvelopeKind.Resolution,
            ),
            SelectNPromptRoute(
                semantic = PromptSemantic.SelectNLibraryPutback,
                shape = dynamicResolutionShape,
                innerPrompt = SelectNInnerPrompt.SelectNInnerParameter,
                envelopeKind = SelectNEnvelopeKind.LibraryPutback,
            ),
            SelectNPromptRoute(
                semantic = PromptSemantic.MutateTopBottom,
                shape = dynamicResolutionShape,
                innerPrompt = SelectNInnerPrompt.GenericSelectN,
                envelopeKind = SelectNEnvelopeKind.MutateTopBottom,
            ),
            SelectNPromptRoute(
                semantic = PromptSemantic.LearnLesson,
                shape = dynamicResolutionShape,
                innerPrompt = SelectNInnerPrompt.LearnInnerParameter,
                envelopeKind = SelectNEnvelopeKind.LearnLesson,
            ),
        ).associateBy { it.semantic }

    fun route(semantic: PromptSemantic): SelectNPromptRoute? = routesBySemantic[semantic]

    fun staticChoice(semantic: PromptSemantic): SelectNPromptRoute? =
        route(semantic)?.takeIf { it.envelopeKind == SelectNEnvelopeKind.StaticChoice }

    fun staticChoiceEnvelope(
        semantic: PromptSemantic,
        req: SelectNReq,
    ): SelectNEnvelope =
        SelectNEnvelope.staticChoice(
            req,
            staticChoice(semantic)?.staticChoice?.outerPromptId ?: error("missing static choice route for $semantic"),
        )
}
