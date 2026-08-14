package leyline.game.bundle

import leyline.bridge.handoff.SearchWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/** Value-only GRE preparation for coordinator-owned library-search windows. */
internal class SearchWindowMaterializer(
    private val seatId: SeatId,
) {
    data class Prepared(
        val bundle: BundleBuilder.BundleResult,
        val transition: ProjectionTransition,
        val closesPlaybackFrame: Boolean,
    )

    fun initial(
        stateMessages: List<GREToClientMessage>,
        requestGameStateId: Int,
        counter: MessageCounter,
        projection: ProjectionState,
        transition: ProjectionTransition,
        window: SearchWindowValue,
    ): Prepared {
        val libraryIds = window.libraryCardIds.map { projection.requireInstanceId(it) }
        val validIds = window.candidateCardIdsByOption.values.map { projection.requireInstanceId(it) }
        val hostId = window.source?.hostCardId?.let { projection.requireInstanceId(it) } ?: 0
        val sourceId =
            window.source
                ?.takeIf { it.abilityOnStack && it.forgeAbilityId != 0 }
                ?.let { projection.requireInstanceId(FrameIdResolver.triggerStackAbilityForgeId(it.forgeAbilityId)) }
                ?: hostId
        val request =
            RequestBuilder.buildSearchReq(
                msgId = counter.nextMsgId(),
                gsId = requestGameStateId,
                systemSeatId = seatId.value,
                sourceInstanceId = sourceId,
                hostCardInstanceId = hostId,
                searchingSeat = seatId.value,
                libraryZoneId = ZoneIds.libraryOf(seatId),
                allLibraryIds = libraryIds,
                validTargetIds = validIds,
                maxFind = window.maxFind,
                allowFailToFind = window.minFind == 0,
                promptId =
                    if (window.source?.let { it.abilityOnStack && it.typeCycling } == true) {
                        PromptIds.SEARCH_TYPECYCLING
                    } else {
                        PromptIds.SEARCH
                    },
            )
        return Prepared(
            bundle = BundleBuilder.BundleResult(stateMessages + request, actionGameStateId = requestGameStateId),
            transition = transition,
            closesPlaybackFrame = true,
        )
    }

    fun resetBaseline(prior: ProjectionState): ProjectionTransition {
        val editor = prior.editor()
        val cursor = editor.viewerCursors[0] ?: leyline.game.state.ViewerProjectionCursor()
        editor.viewerCursors[0] = cursor.copy(previousSnapshot = null)
        return ProjectionTransition(prior.revision, editor.freeze())
    }

    private fun ProjectionState.requireInstanceId(cardId: ForgeCardId): Int =
        identities.forgeIdToInstanceId[cardId]?.value ?: error("Search card ${cardId.value} has no projected instance id")
}
