package leyline.match

import leyline.bridge.types.SeatId
import leyline.game.EngineObservation
import leyline.game.SeatRuntimeFacts
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge

/**
 * Resolved session state -- non-null after bridge connection.
 * Constructed once per handler dispatch inside the synchronized block.
 */
class SessionContext internal constructor(
    val bridge: GameBridge,
    val engine: EngineCutAwaiter,
    private val engineObservation: () -> EngineObservation?,
) {
    internal fun observation(): EngineObservation =
        checkNotNull(engineObservation()) {
            "Engine observation is unavailable before the owner drains a readiness marker"
        }

    internal fun snapshot(): GsmSnapshot = observation().snapshot

    internal fun runtime(seatId: SeatId): SeatRuntimeFacts = observation().runtimeFor(seatId)
}
