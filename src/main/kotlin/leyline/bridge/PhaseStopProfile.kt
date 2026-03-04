package leyline.bridge

import forge.game.phase.PhaseType
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-owned phase-stop state per player.
 * Tracks which phases a player should stop at for priority.
 * Reset on new game/puzzle load; no persistence in v1.
 *
 * Used by engine-side [WebPlayerController] for own-turn phase gating.
 * Separate from [ClientAutoPassState.opponentStops] which drives session-layer
 * opponent-turn stops — see that class for why the split exists.
 *
 * Thread safety: mutations happen on the session thread under `sessionLock`.
 * Engine thread reads via [isEnabled] — callers must ensure happens-before.
 */
class PhaseStopProfile private constructor(
    private val stops: MutableMap<Int, MutableSet<PhaseType>>,
) {
    fun isEnabled(playerId: Int, phase: PhaseType): Boolean =
        stops[playerId]?.contains(phase) == true

    fun setEnabled(playerId: Int, phase: PhaseType, enabled: Boolean) {
        val playerStops = stops.getOrPut(playerId) { mutableSetOf() }
        if (enabled) playerStops.add(phase) else playerStops.remove(phase)
    }

    fun getEnabled(playerId: Int): Set<PhaseType> =
        stops[playerId]?.toSet() ?: emptySet()

    fun toDto(): List<PlayerPhaseStopsDto> =
        stops.map { (playerId, phases) ->
            PlayerPhaseStopsDto(
                playerId = playerId,
                enabled = phases.map { it.name }.sorted(),
            )
        }.sortedBy { it.playerId }

    companion object {
        val CANONICAL_PHASES: Set<PhaseType> = setOf(
            PhaseType.UPKEEP,
            PhaseType.DRAW,
            PhaseType.MAIN1,
            PhaseType.COMBAT_BEGIN,
            PhaseType.COMBAT_DECLARE_ATTACKERS,
            PhaseType.COMBAT_DECLARE_BLOCKERS,
            PhaseType.COMBAT_FIRST_STRIKE_DAMAGE,
            PhaseType.COMBAT_DAMAGE,
            PhaseType.COMBAT_END,
            PhaseType.MAIN2,
            PhaseType.END_OF_TURN,
            PhaseType.CLEANUP,
        )

        fun forPhaseKey(key: String): PhaseType? = try {
            val pt = PhaseType.valueOf(key)
            if (pt in CANONICAL_PHASES) pt else null
        } catch (_: IllegalArgumentException) {
            null
        }

        /**
         * Own-turn defaults for the human player — matches Arena's initial stop set.
         * Client SetSettingsReq with Team scope overrides these.
         */
        private val HUMAN_DEFAULTS = setOf(
            PhaseType.MAIN1,
            PhaseType.COMBAT_DECLARE_ATTACKERS,
            PhaseType.COMBAT_DECLARE_BLOCKERS,
            PhaseType.MAIN2,
        )

        /**
         * AI player needs combat stops so the engine's combat declaration
         * logic runs properly. These are NOT opponent-turn stops for the human —
         * they're the AI's own-turn stops. Opponent-turn stops (what the human
         * sees during AI's turn) are driven by client SetSettingsReq with
         * Opponents scope and stored under the AI player's ID separately
         * in the session-layer check (advanceOrWait).
         */
        private val AI_DEFAULTS = setOf(
            PhaseType.COMBAT_BEGIN,
            PhaseType.COMBAT_DECLARE_ATTACKERS,
            PhaseType.COMBAT_DECLARE_BLOCKERS,
            PhaseType.END_OF_TURN,
        )

        fun createDefaults(humanPlayerId: Int, aiPlayerId: Int): PhaseStopProfile =
            PhaseStopProfile(
                ConcurrentHashMap(
                    mapOf(
                        humanPlayerId to HUMAN_DEFAULTS.toMutableSet(),
                        aiPlayerId to AI_DEFAULTS.toMutableSet(),
                    ),
                ),
            )

        /** Both players get human-style defaults (both interactive). */
        fun createTwoPlayerDefaults(player1Id: Int, player2Id: Int): PhaseStopProfile =
            PhaseStopProfile(
                ConcurrentHashMap(
                    mapOf(
                        player1Id to HUMAN_DEFAULTS.toMutableSet(),
                        player2Id to HUMAN_DEFAULTS.toMutableSet(),
                    ),
                ),
            )

        fun empty(): PhaseStopProfile = PhaseStopProfile(ConcurrentHashMap())
    }
}
