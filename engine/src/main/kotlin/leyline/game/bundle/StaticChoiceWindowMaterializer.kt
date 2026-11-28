package leyline.game.bundle

import leyline.bridge.handoff.StaticChoiceKind
import leyline.bridge.handoff.StaticChoiceWindowValue
import leyline.config.CostChoicePresentation
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.ParameterType
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.PromptParameter
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType
import wotc.mtgo.gre.external.messaging.Messages.StaticList

/** Value-only GRE preparation for coordinator-owned static enum SelectN windows. */
internal class StaticChoiceWindowMaterializer {
    fun prepare(
        context: SettledPromptMaterializationContext,
        window: StaticChoiceWindowValue,
        presentation: CostChoicePresentation,
    ): SettledPromptMaterialization {
        val request = buildRequest(window, context, presentation)
        val predecessor = context.sequence.lastGameStateGsId()
        val state =
            context.gameState
                .toBuilder()
                .apply {
                    if (predecessor in 1 until context.gameStateId) prevGameStateId = predecessor
                }.setPendingMessageCount(1)
                .build()
        val messages =
            listOf(
                context.message(GREMessageType.GameStateMessage_695e) {
                    it.gameStateMessage = state
                },
                context.message(GREMessageType.SelectNreq) {
                    it.selectNReq = request
                    it.prompt =
                        Prompt
                            .newBuilder()
                            .setPromptId(outerPromptId(window.kind))
                            .addParameters(
                                cardIdPromptParameter(
                                    window.sourceForgeCardId?.let { context.requiredInstanceId(it, "Value choice card") }
                                        ?: request.sourceId,
                                ),
                            ).build()
                    it.allowCancel = if (window.allowCancel) AllowCancel.Abort else AllowCancel.No_a526
                },
            )
        return context.prepared(messages, awaitedRequest = messages.last())
    }

    /**
     * A type choice with a type that has no enum value is offered by label under
     * [CostChoicePresentation.ForgeText]. Otherwise the enum list offers only the types
     * that have one.
     */
    private fun offeredByLabel(
        window: StaticChoiceWindowValue,
        presentation: CostChoicePresentation,
    ): Boolean =
        window.kind == StaticChoiceKind.Named ||
            (presentation == CostChoicePresentation.ForgeText && window.options.any { it.protocolValue == null })

    /** Options of [window] the request built for [presentation] lets the player choose. */
    fun shownOptions(
        window: StaticChoiceWindowValue,
        presentation: CostChoicePresentation,
    ): List<Int> =
        window.options
            .filter { offeredByLabel(window, presentation) || it.protocolValue != null }
            .map { it.originalOptionIndex }

    private fun buildRequest(
        window: StaticChoiceWindowValue,
        context: SettledPromptMaterializationContext,
        presentation: CostChoicePresentation,
    ): SelectNReq {
        val byLabel = offeredByLabel(window, presentation)
        val listType =
            if (window.staticList == null || byLabel) {
                SelectionListType.Dynamic
            } else if (window.staticList == StaticList.Colors || window.staticList == StaticList.Parities) {
                SelectionListType.Static
            } else {
                SelectionListType.StaticSubset
            }
        return SelectNReq
            .newBuilder()
            .setContext(SelectionContext.Resolution_a163)
            .setListType(listType)
            .setValidationType(SelectionValidationType.NonRepeatable)
            .setOptionContext(OptionContext.Resolution_a9d7)
            .setMinWeight(Int.MIN_VALUE)
            .setMaxWeight(Int.MAX_VALUE)
            .setMinSel(window.min)
            .setMaxSel(window.max)
            .setPrompt(
                Prompt.newBuilder().apply {
                    if (byLabel) {
                        window.options.forEach { option ->
                            addParameters(
                                PromptParameter
                                    .newBuilder()
                                    .setParameterName(
                                        "Choice",
                                    ).setType(ParameterType.NonLocalizedString)
                                    .setStringValue(checkNotNull(option.label)),
                            )
                        }
                    }
                },
            ).apply {
                window.sourceForgeCardId?.let { sourceId = context.requiredInstanceId(it, "StaticChoice source") }
                window.staticList?.takeUnless { byLabel }?.let { staticList = it }
                if (window.sourceForgeAbilityId != 0) {
                    sourceId =
                        context.requiredInstanceId(
                            FrameIdResolver.triggerStackAbilityForgeId(window.sourceForgeAbilityId),
                            "Dungeon venture ability",
                        )
                }
                if (listType == SelectionListType.Dynamic) {
                    idType =
                        when (window.kind.takeUnless { byLabel }) {
                            null, StaticChoiceKind.Named -> IdType.PromptParameterIndex
                            StaticChoiceKind.Dungeon -> IdType.CardGrpId
                            StaticChoiceKind.DungeonRoom,
                            StaticChoiceKind.Color,
                            StaticChoiceKind.Subtype,
                            StaticChoiceKind.CardType,
                            StaticChoiceKind.Parity,
                            -> IdType.AbilityGrpId
                        }
                }
                if (byLabel) {
                    addAllIds(window.options.map { it.originalOptionIndex + 1 })
                } else if (listType != SelectionListType.Static) {
                    addAllIds(window.options.mapNotNull { it.protocolValue })
                }
            }.build()
    }

    private fun outerPromptId(kind: StaticChoiceKind): Int =
        when (kind) {
            StaticChoiceKind.Color -> PromptIds.CHOOSE_COLOR
            StaticChoiceKind.Named,
            StaticChoiceKind.Dungeon,
            StaticChoiceKind.DungeonRoom,
            StaticChoiceKind.Subtype,
            StaticChoiceKind.CardType,
            StaticChoiceKind.Parity,
            -> PromptIds.CHOOSE_TYPE
        }
}
