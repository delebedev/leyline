package leyline.bridge.coord

import forge.game.Game
import leyline.bridge.types.SeatId
import leyline.game.PlaybackCutRequest
import leyline.game.bundle.BundleBuilder
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.state.GameBridge

/** Shell-side combat frame inputs derived from the closed journal and final Forge state. */
internal object PlaybackFrameSpecMaterializer {
    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    fun materialize(
        bridge: GameBridge,
        game: Game,
        seatId: SeatId,
        request: PlaybackCutRequest,
        events: FrameEventLog,
    ): List<BundleBuilder.PlaybackFrameSpec> {
        val matchSeats = bridge.gameSeatIds()
        val sourceControllerSeats =
            events.events
                .mapNotNull { event ->
                    when (event) {
                        is GameEvent.DamageDealtToCard -> event.sourceCardId
                        is GameEvent.DamageDealtToPlayer -> event.sourceCardId
                        else -> null
                    }
                }.distinct()
                .mapNotNull { sourceId ->
                    val controller = bridge.findCard(sourceId)?.controller ?: return@mapNotNull null
                    matchSeats
                        .firstOrNull { candidate -> bridge.getPlayer(SeatId(candidate)) == controller }
                        ?.let { sourceId to it }
                }.toMap()
        val turnPlayer = game.phaseHandler.playerTurn
        val currentTurnSeat = turnPlayer?.let { player -> matchSeats.firstOrNull { bridge.getPlayer(SeatId(it)) == player } }
        return CombatPlaybackFramePlanner.plan(request, events, seatId, currentTurnSeat, matchSeats, sourceControllerSeats)
    }
}
