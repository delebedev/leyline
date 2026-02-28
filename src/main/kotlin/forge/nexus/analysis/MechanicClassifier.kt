package forge.nexus.analysis

import forge.nexus.game.NexusGameEvent
import kotlinx.serialization.Serializable

/**
 * Classifies [NexusGameEvent] streams into mechanic tags for analysis.
 *
 * Simple lookup table: event type → mechanic tag. Counts occurrences.
 */
object MechanicClassifier {

    @Serializable
    data class MechanicCount(
        val type: String,
        val count: Int,
    )

    /** Known mechanic tag for each NexusGameEvent type. */
    fun classify(event: NexusGameEvent): String = when (event) {
        is NexusGameEvent.LandPlayed -> "land_play"
        is NexusGameEvent.SpellCast -> "spell_cast"
        is NexusGameEvent.SpellResolved -> if (event.hasFizzled) "spell_fizzle" else "spell_resolve"
        is NexusGameEvent.ZoneChanged -> "zone_change"
        is NexusGameEvent.CardTapped -> if (event.tapped) "tap" else "untap"
        is NexusGameEvent.DamageDealtToCard -> "damage_to_creature"
        is NexusGameEvent.DamageDealtToPlayer -> if (event.combat) "combat_damage" else "noncombat_damage"
        is NexusGameEvent.LifeChanged -> "life_change"
        is NexusGameEvent.AttackersDeclared -> "attackers_declared"
        is NexusGameEvent.BlockersDeclared -> "blockers_declared"
        is NexusGameEvent.CardDestroyed -> "destroy"
        is NexusGameEvent.CardSacrificed -> "sacrifice"
        is NexusGameEvent.CardBounced -> "bounce"
        is NexusGameEvent.CardExiled -> "exile"
        is NexusGameEvent.CardDiscarded -> "discard"
        is NexusGameEvent.CardMilled -> "mill"
        is NexusGameEvent.SpellCountered -> "counter"
        is NexusGameEvent.TokenCreated -> "token_create"
        is NexusGameEvent.TokenDestroyed -> "token_destroy"
        is NexusGameEvent.CountersChanged -> "counters_change"
        is NexusGameEvent.LibraryShuffled -> "shuffle"
        is NexusGameEvent.Scry -> "scry"
        is NexusGameEvent.Surveil -> "surveil"
        is NexusGameEvent.CardAttached -> "attachment"
        is NexusGameEvent.CardDetached -> "detachment"
        is NexusGameEvent.CardsRevealed -> "reveal"
        is NexusGameEvent.CombatEnded -> "combat_end"
        is NexusGameEvent.PowerToughnessChanged -> "pt_change"
    }

    /**
     * Classify a stream of events and return sorted mechanic counts.
     */
    fun classifyAll(events: List<NexusGameEvent>): List<MechanicCount> =
        events
            .groupingBy { classify(it) }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { MechanicCount(it.key, it.value) }

    /**
     * Classify from JSONL event stream entries (forge stream only).
     * Takes event type strings directly (already classified at record time).
     */
    fun classifyFromStrings(eventTypes: List<String>): List<MechanicCount> =
        eventTypes
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { MechanicCount(it.key, it.value) }

    /** Map from category string (annotation-level) to mechanic tag. */
    fun categoryToMechanic(category: String): String = when (category) {
        "PlayLand" -> "land_play"
        "CastSpell" -> "spell_cast"
        "Resolve" -> "spell_resolve"
        "Destroy" -> "destroy"
        "Sacrifice" -> "sacrifice"
        "Exile" -> "exile"
        "Discard" -> "discard"
        "Mill" -> "mill"
        "Bounce" -> "bounce"
        "Counter" -> "counter"
        "Draw" -> "draw"
        "Damage" -> "damage_to_creature"
        "LifeChange" -> "life_change"
        "TapUntap" -> "tap"
        "Counters" -> "counters_change"
        "CreateToken" -> "token_create"
        "Scry" -> "scry"
        "Surveil" -> "surveil"
        "Shuffle" -> "shuffle"
        else -> category.lowercase().replace(Regex("[^a-z0-9]"), "_")
    }
}
