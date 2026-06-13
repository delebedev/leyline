package leyline.game.bundle

import leyline.bridge.handoff.PromptSemantic
import leyline.game.mapping.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.StaticList

enum class SelectNReason {
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
    StaticParityChoice,
}

internal data class SelectNShape(
    val context: SelectionContext,
    val listType: SelectionListType,
    val optionContext: OptionContext,
)

internal data class StaticChoiceSelectNRoute(
    val semantic: PromptSemantic,
    val reason: SelectNReason,
    val shape: SelectNShape,
    val outerPromptId: Int,
    val choiceDomain: Int,
)

internal object SelectNPromptRoutes {
    private val staticResolutionShape =
        SelectNShape(
            SelectionContext.Resolution_a163,
            SelectionListType.Static,
            OptionContext.Resolution_a9d7,
        )

    private val routesBySemantic =
        listOf(
            StaticChoiceSelectNRoute(
                semantic = PromptSemantic.StaticColorChoice,
                reason = SelectNReason.StaticColorChoice,
                shape = staticResolutionShape,
                outerPromptId = PromptIds.CHOOSE_COLOR,
                choiceDomain = 6,
            ),
            StaticChoiceSelectNRoute(
                semantic = PromptSemantic.StaticSubtypeChoice,
                reason = SelectNReason.StaticSubtypeChoice,
                shape = staticResolutionShape.copy(listType = SelectionListType.StaticSubset),
                outerPromptId = PromptIds.CHOOSE_TYPE,
                choiceDomain = 5,
            ),
            StaticChoiceSelectNRoute(
                semantic = PromptSemantic.StaticParityChoice,
                reason = SelectNReason.StaticParityChoice,
                shape = staticResolutionShape,
                outerPromptId = PromptIds.CHOOSE_TYPE,
                choiceDomain = StaticList.Parities.number,
            ),
        ).associateBy { it.semantic }

    fun staticChoice(semantic: PromptSemantic): StaticChoiceSelectNRoute? = routesBySemantic[semantic]
}
