package leyline.bridge.coord

import forge.game.zone.ZoneType
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.SearchWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind

/** Engine-thread capture for one immutable library-search window. */
internal class SearchWindowCapture(
    private val owner: MatchCutCoordinator,
) {
    fun capture(request: PromptRequest): SearchWindowValue {
        val player = owner.bridge.getPlayer(owner.humanSeat) ?: error("Search player unavailable")
        return SearchWindowValue(
            libraryCardIds = player.getZone(ZoneType.Library).cards.map { ForgeCardId(it.id) },
            candidateCardIdsByOption =
                request.candidateRefs
                    .filter { it.kind == PromptCandidateKind.Card }
                    .associate { it.index to ForgeCardId(it.entityId) },
            optionCount = request.options.size,
            minFind = request.min,
            maxFind = request.max,
            defaultIndex = request.defaultIndex,
            source = request.searchSource,
        )
    }
}
