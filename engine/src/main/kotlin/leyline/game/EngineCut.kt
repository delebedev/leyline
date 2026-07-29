package leyline.game

import leyline.bridge.types.ForgeCardId
import leyline.game.event.FrameEventLog
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import java.util.ArrayDeque

internal data class EngineCutCheckpoint(
    val generation: Long,
    val sequence: Long,
)

internal enum class InteractionReadiness {
    ACTION,
    PROMPT,
    DAMAGE_ASSIGNMENT,
    OPTIONAL_ACTION,
    NUMERIC_INPUT,
    GAME_OVER,
}

internal sealed interface EngineCut {
    val checkpoint: EngineCutCheckpoint

    data class Observation(
        override val checkpoint: EngineCutCheckpoint,
        val value: PlaybackYield,
    ) : EngineCut

    data class InteractionReady(
        override val checkpoint: EngineCutCheckpoint,
        val kind: InteractionReadiness,
        val observation: EngineObservation,
    ) : EngineCut
}

/**
 * One ordered handoff from the engine domain to the match owner.
 *
 * Publication and draining share one monitor, so sequence order is queue order.
 */
internal class EngineCutQueue {
    private val cuts = ArrayDeque<EngineCut>()
    private var generation = 0L
    private var nextSequence = 0L

    @Synchronized
    fun beginGeneration(): Long {
        generation += 1
        nextSequence = 0
        cuts.clear()
        return generation
    }

    @Synchronized
    fun publishObservation(value: PlaybackYield): EngineCutCheckpoint {
        check(value.sourceGeneration == generation) {
            "Playback observation belongs to generation ${value.sourceGeneration}, current generation is $generation"
        }
        val checkpoint = EngineCutCheckpoint(generation, nextSequence++)
        cuts.addLast(EngineCut.Observation(checkpoint, value))
        return checkpoint
    }

    @Synchronized
    fun publishReady(
        kind: InteractionReadiness,
        observation: EngineObservation,
    ): EngineCutCheckpoint {
        val checkpoint = EngineCutCheckpoint(generation, nextSequence++)
        cuts.addLast(EngineCut.InteractionReady(checkpoint, kind, observation))
        return checkpoint
    }

    @Synchronized
    fun latestReady(): EngineCut.InteractionReady? = cuts.filterIsInstance<EngineCut.InteractionReady>().lastOrNull()

    @Synchronized
    fun latestCheckpoint(): EngineCutCheckpoint = EngineCutCheckpoint(generation, nextSequence - 1)

    @Synchronized
    fun peekThrough(checkpoint: EngineCutCheckpoint): EngineCut? {
        check(checkpoint.generation == generation) {
            "Engine cut generation changed before owner drain"
        }
        return cuts.firstOrNull()?.takeIf { it.checkpoint.sequence <= checkpoint.sequence }
    }

    @Synchronized
    fun acknowledge(cut: EngineCut) {
        check(cut.checkpoint.generation == generation) {
            "Engine cut generation changed before owner acknowledgement"
        }
        check(cuts.firstOrNull() === cut) {
            "Engine cuts must be acknowledged in FIFO order"
        }
        cuts.removeFirst()
    }

    @Synchronized
    fun hasPending(): Boolean = cuts.isNotEmpty()
}

internal data class PlaybackYield(
    val sourceGeneration: Long,
    val cutReason: PlaybackCutReason,
    val observation: EngineObservation,
    val events: FrameEventLog,
    val reservation: GameBridge.BundleFrameReservation,
    val turnStarted: Boolean = false,
    val combatFrames: List<CombatYieldFrame> = emptyList(),
    val naiveActions: List<NaiveGsmAction> = emptyList(),
) {
    val snapshot: GsmSnapshot get() = observation.snapshot
}

internal enum class NaiveGsmActionKind {
    CAST,
    CAST_ADVENTURE,
    CAST_MDFC,
    ACTIVATE_MANA,
    PASS,
    FLOAT_MANA,
}

/** Protocol-neutral action shape materialized at the same engine cut as [PlaybackYield]. */
internal data class NaiveGsmAction(
    val kind: NaiveGsmActionKind,
    val forgeCardId: ForgeCardId? = null,
    val abilityGrpId: Int = 0,
    val sourceForgeCardId: ForgeCardId? = null,
    val manaCost: List<ManaRequirementValue> = emptyList(),
)

internal data class ManaRequirementValue(
    val colors: List<Int>,
    val count: Int,
    val abilityGrpId: Int = 0,
)

internal data class CombatYieldFrame(
    val events: FrameEventLog,
    val lifeTotals: Map<Int, Int>,
)

internal enum class PlaybackCutReason {
    LAND_PLAYED,
    SPELL_ABILITY_CAST,
    SPELL_RESOLVED,
    STACK_EXIT_COMPLETION,
    CARD_COUNTERS,
    PLAYER_POISONED,
    TURN_BEGAN,
    TURN_PHASE,
    ATTACKERS_DECLARED,
    BLOCKERS_DECLARED,
    COMBAT_ENDED,
}
