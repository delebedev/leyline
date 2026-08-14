package leyline.game.bundle

import leyline.bridge.handoff.CardSelectKind
import leyline.bridge.handoff.CardSelectOriginZone
import leyline.bridge.handoff.CardSelectWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.PromptIds
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType
import wotc.mtgo.gre.external.messaging.Messages.Visibility

/** Value-only GRE preparation for coordinator-owned card-backed SelectN windows. */
internal class CardSelectWindowMaterializer(
    private val seatId: Int,
) {
    data class Prepared(
        val bundle: BundleBuilder.BundleResult,
        val transition: ProjectionTransition,
        val closesPlaybackFrame: Boolean,
    )

    fun prepare(
        gameState: GameStateMessage,
        gameStateId: Int,
        counter: MessageCounter,
        projection: ProjectionState,
        transition: ProjectionTransition,
        window: CardSelectWindowValue,
    ): Prepared {
        val request = buildRequest(window, projection)
        if (window.kind in privateCandidateKinds) requirePrivateCandidates(gameState, request.idsList)
        val envelope = envelope(window, request)
        val state = gameState.toBuilder().setPendingMessageCount(1).build()
        val messages =
            listOf(
                makeGRE(GREMessageType.GameStateMessage_695e, gameStateId, counter.nextMsgId()) {
                    it.gameStateMessage = state
                },
                makeGRE(GREMessageType.SelectNreq, gameStateId, counter.nextMsgId()) {
                    it.selectNReq = envelope.req
                    it.prompt = envelope.prompt
                    if (envelope.allowCancel != AllowCancel.None_a526) it.allowCancel = envelope.allowCancel
                },
            )
        return Prepared(BundleBuilder.BundleResult(messages, actionGameStateId = gameStateId), transition, true)
    }

    private fun buildRequest(
        window: CardSelectWindowValue,
        projection: ProjectionState,
    ): SelectNReq {
        val discard = window.kind == CardSelectKind.Discard
        return SelectNReq
            .newBuilder()
            .setContext(if (discard) SelectionContext.Discard_a163 else SelectionContext.Resolution_a163)
            .setListType(if (discard) SelectionListType.Static else SelectionListType.Dynamic)
            .setValidationType(SelectionValidationType.NonRepeatable)
            .setOptionContext(if (discard) OptionContext.Payment else OptionContext.Resolution_a9d7)
            .setMinWeight(Int.MIN_VALUE)
            .setMaxWeight(Int.MAX_VALUE)
            .setIdType(IdType.InstanceId_ab2c)
            .setMinSel(if (window.kind == CardSelectKind.Learn && window.candidates.isNotEmpty()) 1 else window.min)
            .setMaxSel(window.max)
            .addAllIds(window.candidates.map { projection.requireInstanceId(it.forgeCardId) })
            .apply {
                window.sourceForgeCardId?.let { sourceId = projection.requireInstanceId(it) }
                when (window.kind) {
                    CardSelectKind.LegendRule -> {
                        prompt = Prompt.getDefaultInstance()
                        sourceId = PromptIds.SELECT_N_LEGEND_RULE_SOURCE
                    }
                    CardSelectKind.LibraryPutback -> setSelectNInnerPrompt(PromptIds.SELECT_N_INNER_PARAMETER)
                    CardSelectKind.ManifestDread -> {
                        addAllUnfilteredIds(idsList)
                        setSelectNInnerPrompt(PromptIds.MANIFEST_DREAD_INNER_PARAMETER)
                    }
                    CardSelectKind.Resolution,
                    CardSelectKind.ResolutionMapped,
                    -> {
                        addAllUnfilteredIds(idsList)
                        setSelectNInnerPrompt(PromptIds.SELECT_N_INNER_PARAMETER)
                    }
                    CardSelectKind.Learn -> setSelectNInnerPrompt(PromptIds.SELECT_N_LEARN_INNER_PARAMETER)
                    CardSelectKind.Discard -> prompt = Prompt.newBuilder().setPromptId(PromptIds.DISCARD_COST).build()
                    CardSelectKind.Suspect -> setSelectNInnerPrompt(PromptIds.SELECT_N_INNER_PARAMETER)
                    CardSelectKind.SacrificeEffect,
                    CardSelectKind.MutateTopBottom,
                    -> prompt = Prompt.newBuilder().setPromptId(PromptIds.SELECT_N).build()
                }
            }.build()
    }

    private fun envelope(
        window: CardSelectWindowValue,
        request: SelectNReq,
    ): SelectNEnvelope =
        when (window.kind) {
            CardSelectKind.LegendRule -> SelectNEnvelope.legendRule(request)
            CardSelectKind.LibraryPutback -> SelectNEnvelope.libraryPutback(request)
            CardSelectKind.ManifestDread -> SelectNEnvelope.manifestDread(request)
            CardSelectKind.Resolution,
            CardSelectKind.ResolutionMapped,
            -> SelectNEnvelope.resolution(request)
            CardSelectKind.Learn ->
                SelectNEnvelope.learnLesson(
                    request,
                    if (window.candidates.any { it.originZone == CardSelectOriginZone.Hand }) {
                        PromptIds.LEARN_LESSON_OR_DISCARD
                    } else {
                        PromptIds.LEARN_LESSON_ONLY
                    },
                )
            CardSelectKind.Discard,
            CardSelectKind.SacrificeEffect,
            -> SelectNEnvelope.default(request)
            CardSelectKind.Suspect -> SelectNEnvelope.suspectChoice(request)
            CardSelectKind.MutateTopBottom -> SelectNEnvelope.mutateTopBottom(request)
        }

    private fun ProjectionState.requireInstanceId(cardId: ForgeCardId): Int =
        identities.forgeIdToInstanceId[cardId]?.value ?: error("CardSelect card ${cardId.value} has no projected instance id")

    private fun requirePrivateCandidates(
        gameState: GameStateMessage,
        candidateIds: List<Int>,
    ) {
        val objectsById = gameState.gameObjectsList.associateBy { it.instanceId }
        candidateIds.forEach { candidateId ->
            val candidate = checkNotNull(objectsById[candidateId]) { "Private CardSelect candidate $candidateId was not projected" }
            check(candidate.visibility == Visibility.Private && candidate.viewersList == listOf(seatId)) {
                "Private CardSelect candidate $candidateId must be private to its chooser"
            }
        }
    }

    private fun makeGRE(
        type: GREMessageType,
        gameStateId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage =
        GREToClientMessage
            .newBuilder()
            .setType(type)
            .setMsgId(msgId)
            .setGameStateId(gameStateId)
            .addSystemSeatIds(seatId)
            .also(configure)
            .build()

    private companion object {
        val privateCandidateKinds = setOf(CardSelectKind.ManifestDread, CardSelectKind.Resolution, CardSelectKind.Learn)
    }
}
