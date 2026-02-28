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

    // -- Group A: zone-transition disambiguation --
    // These replace generic ZoneChanged for specific zone pairs, enabling
    // direct category mapping without the zoneChangedCategory() fallback.

    /** A permanent was destroyed (BF→GY, not sacrifice). */
    data class CardDestroyed(
        val forgeCardId: Int,
        val seatId: Int,
    ) : NexusGameEvent

    /** A permanent was sacrificed (BF→GY via sacrifice effect). */
    data class CardSacrificed(
        val forgeCardId: Int,
        val seatId: Int,
    ) : NexusGameEvent

    /** A permanent was bounced (BF→Hand or BF→Library). */
    data class CardBounced(
        val forgeCardId: Int,
        val seatId: Int,
    ) : NexusGameEvent

    /** A card was exiled (any zone → Exile). */
    data class CardExiled(
        val forgeCardId: Int,
        val seatId: Int,
    ) : NexusGameEvent

    /** A card was discarded (Hand→GY). */
    data class CardDiscarded(
        val forgeCardId: Int,
        val seatId: Int,
    ) : NexusGameEvent

    /** A card was milled (Library→GY). */
    data class CardMilled(
        val forgeCardId: Int,
        val seatId: Int,
    ) : NexusGameEvent

    /** A spell was countered (Stack→GY without resolving).
     *  Not wired from a dedicated Forge event — inferred from zone pair. */
    data class SpellCountered(
        val forgeCardId: Int,
        val seatId: Int,
    ) : NexusGameEvent

    /** A token was created.
     *  Wired from GameEventTokenCreated (enriched with List<Card> tokens). */
    data class TokenCreated(
        val forgeCardId: Int,
        val seatId: Int,
        val sourceForgeCardId: Int? = null,
    ) : NexusGameEvent

    /** A token was destroyed (left the battlefield).
     *  Wired from GameEventCardChangeZone when card.isToken && from=Battlefield. */
    data class TokenDestroyed(
        val forgeCardId: Int,
        val seatId: Int,
    ) : NexusGameEvent

    // -- Group A+: attachment events --

    /** A card was attached to another permanent (aura enchanting, equipment equipping). */
    data class CardAttached(
        val forgeCardId: Int,
        val targetForgeId: Int,
        val seatId: Int,
    ) : NexusGameEvent

    /** A card was detached from its target (aura falling off, equipment unequipped). */
    data class CardDetached(
        val forgeCardId: Int,
        val seatId: Int,
    ) : NexusGameEvent

    // -- Group B: annotation-producing events --

    /** Counters added or removed on a card (+1/+1, loyalty, poison, stun, etc.). */
    data class CountersChanged(
        val forgeCardId: Int,
        val counterType: String,
        val oldCount: Int,
        val newCount: Int,
    ) : NexusGameEvent

    /** A card's power or toughness changed (pump, anthem, equipment, SBA). */
    data class PowerToughnessChanged(
        val forgeCardId: Int,
        val oldPower: Int,
        val newPower: Int,
        val oldToughness: Int,
        val newToughness: Int,
    ) : NexusGameEvent

    /** A player's library was shuffled. */
    data class LibraryShuffled(
        val seatId: Int,
    ) : NexusGameEvent

    /** A player scried (looked at top N, put some on top / some on bottom). */
    data class Scry(
        val seatId: Int,
        val topCount: Int,
        val bottomCount: Int,
    ) : NexusGameEvent

    /** A player surveilled (looked at top N, put some in library / some in graveyard). */
    data class Surveil(
        val seatId: Int,
        val toLibrary: Int,
        val toGraveyard: Int,
    ) : NexusGameEvent

    // -- Group B+: reveal events --
    // Not from EventBus — captured via InteractivePromptBridge.drainReveals()
    // in WebPlayerController.reveal() override.

    /** Cards were revealed to all players (e.g. draw-and-reveal, Explore, etc.). */
    data class CardsRevealed(
        val forgeCardIds: List<Int>,
        val ownerSeatId: Int,
    ) : NexusGameEvent

    // -- Group C: combat enrichment --

    /** Combat phase ended — signal to clear combat state. */
    data object CombatEnded : NexusGameEvent
}
