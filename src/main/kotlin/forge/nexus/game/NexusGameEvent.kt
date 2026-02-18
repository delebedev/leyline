package forge.nexus.game

import forge.game.zone.ZoneType

/**
 * Protocol-oriented game events captured from the Forge engine's EventBus.
 *
 * These replace the "infer from diff" heuristic in [StateMapper.buildFromGame]:
 * instead of comparing zone snapshots and guessing what happened, the
 * [GameEventCollector] captures rich events as they fire and the annotation
 * builder converts them directly into client protocol annotations.
 *
 * All IDs here are **Forge card IDs** (not client instanceIds). The bridge
 * resolves them to instanceIds at annotation-build time so the event layer
 * stays decoupled from protocol ID allocation.
 */
sealed interface NexusGameEvent {

    /** A land was played from hand to battlefield. */
    data class LandPlayed(
        val forgeCardId: Int,
        val seatId: Int,
    ) : NexusGameEvent

    /** A spell or ability was cast (hand/battlefield → stack). */
    data class SpellCast(
        val forgeCardId: Int,
        val seatId: Int,
    ) : NexusGameEvent

    /** A spell or ability finished resolving (stack → battlefield/graveyard/exile). */
    data class SpellResolved(
        val forgeCardId: Int,
        val hasFizzled: Boolean,
    ) : NexusGameEvent

    /** A card changed zones (generic — covers destroy, exile, sacrifice, bounce, etc.). */
    data class ZoneChanged(
        val forgeCardId: Int,
        val from: ZoneType,
        val to: ZoneType,
    ) : NexusGameEvent

    /** A permanent was tapped or untapped. */
    data class CardTapped(
        val forgeCardId: Int,
        val tapped: Boolean,
    ) : NexusGameEvent

    /** Damage was dealt to a creature. */
    data class DamageDealtToCard(
        val sourceForgeId: Int,
        val targetForgeId: Int,
        val amount: Int,
    ) : NexusGameEvent

    /** Damage was dealt to a player. */
    data class DamageDealtToPlayer(
        val sourceForgeId: Int,
        val targetSeatId: Int,
        val amount: Int,
        val combat: Boolean,
    ) : NexusGameEvent

    /** A player's life total changed. */
    data class LifeChanged(
        val seatId: Int,
        val oldLife: Int,
        val newLife: Int,
    ) : NexusGameEvent

    /** Attackers were declared. */
    data class AttackersDeclared(
        val attackerForgeIds: List<Int>,
        val seatId: Int,
    ) : NexusGameEvent

    /** Blockers were declared. */
    data class BlockersDeclared(
        val blockerForgeIds: List<Int>,
        val seatId: Int,
    ) : NexusGameEvent
}
