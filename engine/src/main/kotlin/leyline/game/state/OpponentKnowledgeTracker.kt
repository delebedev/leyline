package leyline.game.state

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.event.GameEvent
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot

/** Hidden card identities that an opponent may continue to recognize. */
class OpponentKnowledgeTracker {
    data class State(
        val known: Map<ForgeCardId, InstanceId>,
    )

    data class Transition(
        val version: Long,
        val next: State,
    )

    private var version = 0L
    private var state = State(emptyMap())

    fun plan(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
        events: List<GameEvent>,
    ): Pair<List<InstanceId>, Transition> {
        val shuffledSeats = events.filterIsInstance<GameEvent.LibraryShuffled>().mapTo(mutableSetOf()) { it.seatId.value }
        val shuffledLibraryZoneIds = shuffledSeats.map(ZoneIds::libraryOf).toSet()
        val hiddenCards =
            snap.zones.values
                .asSequence()
                .filter { it.id in hiddenZoneIds && it.id !in shuffledLibraryZoneIds }
                .flatMap { it.contents.asSequence() }
                .associateWith(frameIds::cardIid)

        val next = state.known.toMutableMap()
        next.entries.removeIf { (cardId, iid) -> hiddenCards[cardId] != iid }
        events.filterIsInstance<GameEvent.CardsRevealed>().filter { it.viewerSeatId != it.ownerSeatId }.forEach { reveal ->
            reveal.cardIds.forEach { cardId -> hiddenCards[cardId]?.let { next[cardId] = it } }
        }
        val nextState = State(next)
        return nextState.known.values.toList() to Transition(version, nextState)
    }

    fun canCommit(transition: Transition): Boolean = transition.version == version

    fun commit(transition: Transition): Boolean =
        if (!canCommit(transition)) {
            false
        } else {
            state = transition.next
            version++
            true
        }

    fun clear() {
        state = State(emptyMap())
        version++
    }

    private companion object {
        val hiddenZoneIds =
            setOf(
                ZoneIds.P1_HAND,
                ZoneIds.P2_HAND,
                ZoneIds.P1_LIBRARY,
                ZoneIds.P2_LIBRARY,
                ZoneIds.P1_SIDEBOARD,
                ZoneIds.P2_SIDEBOARD,
            )
    }
}
