package leyline.bridge.coord

import forge.game.zone.ZoneType
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.SearchGroupValue
import leyline.bridge.handoff.SearchWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind

/** Engine-thread capture for one immutable library-search window. */
internal class SearchWindowCapture(
    private val owner: MatchCutCoordinator,
) {
    private companion object {
        const val FIRST_GROUP_ID = 5003
    }

    fun capture(request: PromptRequest): SearchWindowValue {
        val candidateIds =
            request.candidateRefs
                .filter { it.kind == PromptCandidateKind.Card }
                .associate { it.index to ForgeCardId(it.entityId) }
        val candidateSeats =
            candidateIds.values
                .map { id ->
                    val card = owner.bridge.findCard(id) ?: error("Search candidate unavailable")
                    val zone = card.zone ?: error("Search candidate zone unavailable")
                    require(zone.zoneType == ZoneType.Library) { "Search candidates must be in a library" }
                    owner.bridge.seatOf(zone.player) ?: error("Search library owner unavailable")
                }.distinct()
        val searchedSeat =
            request.searchedSeatId ?: candidateSeats.singleOrNull()
                ?: error("Search requires one known library owner")
        require(candidateSeats.all { it == searchedSeat }) { "Search candidates must belong to the searched library" }
        val player = owner.bridge.getPlayer(searchedSeat) ?: error("Search player unavailable")
        return SearchWindowValue(
            searchedSeatId = searchedSeat,
            libraryCardIds = player.getZone(ZoneType.Library).cards.map { ForgeCardId(it.id) },
            candidateCardIdsByOption = candidateIds,
            optionCount = request.options.size,
            minFind = request.min,
            maxFind = request.max,
            defaultIndex = request.defaultIndex,
            source = request.searchSource,
            groups =
                request.searchGroupOptionIndices.mapIndexed { index, options ->
                    SearchGroupValue(
                        groupId = FIRST_GROUP_ID + index,
                        candidateCardIdsByOption =
                            options.associateWith {
                                candidateIds[it]
                                    ?: error("Search group references unknown option $it")
                            },
                    )
                },
        )
    }
}
