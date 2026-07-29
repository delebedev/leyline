package leyline.game

import leyline.bridge.types.PlayerLossCause
import leyline.bridge.types.PriorityActionFacts
import leyline.bridge.types.SeatId
import leyline.game.snapshot.GsmSnapshot

/**
 * Immutable worker view available after the owner drains its engine-cut marker.
 *
 * The snapshot feeds frame compilation. Runtime facts feed owner policy without
 * a follow-up read into the live engine graph.
 */
internal data class EngineObservation(
    val snapshot: GsmSnapshot,
    val seats: Map<SeatId, SeatRuntimeFacts>,
    val hasPendingEvents: Boolean,
) {
    fun runtimeFor(seatId: SeatId): SeatRuntimeFacts = seats[seatId] ?: SeatRuntimeFacts.absent()

    companion object {
        fun forTest(
            snapshot: GsmSnapshot = GsmSnapshot.forTest(),
            seats: Map<SeatId, SeatRuntimeFacts> = emptyMap(),
            hasPendingEvents: Boolean = false,
        ): EngineObservation = EngineObservation(snapshot, seats, hasPendingEvents)
    }
}

internal data class SeatRuntimeFacts(
    val phase: String?,
    val turn: Int,
    val isGameOver: Boolean,
    val hasPlayer: Boolean,
    val isPlayerTurn: Boolean,
    val stackEmpty: Boolean,
    val combatHasAttackers: Boolean,
    val priorityActions: PriorityActionFacts,
    val naiveActions: List<NaiveGsmAction>,
    val combatDeclarations: CombatDeclarationFacts,
    val won: Boolean,
    val lossCause: PlayerLossCause?,
) {
    val isOpponentTurn: Boolean get() = hasPlayer && !isPlayerTurn

    companion object {
        fun absent(): SeatRuntimeFacts =
            SeatRuntimeFacts(
                phase = null,
                turn = 0,
                isGameOver = true,
                hasPlayer = false,
                isPlayerTurn = false,
                stackEmpty = true,
                combatHasAttackers = false,
                priorityActions = PriorityActionFacts(hasLegalNonManaAction = false),
                naiveActions = emptyList(),
                combatDeclarations = CombatDeclarationFacts(),
                won = false,
                lossCause = null,
            )
    }
}
