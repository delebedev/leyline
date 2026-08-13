package leyline.game.state

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.event.GameEvent
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot

object OpponentKnowledgeTracker {
    data class State(
        val known: Map<ForgeCardId, InstanceId>,
    )

    fun plan(
        prior: State,
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
        events: List<GameEvent>,
    ): Pair<List<InstanceId>, State> {
        val shuffledSeats = events.filterIsInstance<GameEvent.LibraryShuffled>().mapTo(mutableSetOf()) { it.seatId.value }
        val shuffledLibraryZoneIds = shuffledSeats.map(ZoneIds::libraryOf).toSet()
        val hiddenCards =
            snap.zones.values
                .asSequence()
                .filter { it.id in hiddenZoneIds && it.id !in shuffledLibraryZoneIds }
                .flatMap { it.contents.asSequence() }
                .associateWith(frameIds::cardIid)
        val next = prior.known.toMutableMap()
        next.entries.removeIf { (cardId, iid) -> hiddenCards[cardId] != iid }
        events.filterIsInstance<GameEvent.CardsRevealed>().filter { it.viewerSeatId != it.ownerSeatId }.forEach { reveal ->
            reveal.cardIds.forEach { cardId -> hiddenCards[cardId]?.let { next[cardId] = it } }
        }
        val state = State(next.toMap())
        return state.known.values.toList() to state
    }

    private val hiddenZoneIds =
        setOf(
            ZoneIds.P1_HAND,
            ZoneIds.P2_HAND,
            ZoneIds.P1_LIBRARY,
            ZoneIds.P2_LIBRARY,
            ZoneIds.P1_SIDEBOARD,
            ZoneIds.P2_SIDEBOARD,
        )
}
