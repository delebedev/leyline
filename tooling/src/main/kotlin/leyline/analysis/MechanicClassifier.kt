package leyline.analysis

import kotlinx.serialization.Serializable
import leyline.game.GameEvent

/**
 * Classifies [GameEvent] streams into mechanic tags for analysis.
 *
 * Simple lookup table: event type → mechanic tag. Counts occurrences.
 */
object MechanicClassifier {

    @Serializable
    data class MechanicCount(
        val type: String,
        val count: Int,
    )

    /** Known mechanic tag for each GameEvent type. */
    fun classify(event: GameEvent): String = when (event) {
        is GameEvent.LandPlayed -> "land_play"
        is GameEvent.SpellCast -> "spell_cast"
        is GameEvent.SpellResolved -> if (event.hasFizzled) "spell_fizzle" else "spell_resolve"
        is GameEvent.ZoneChanged -> "zone_change"
        is GameEvent.CardTapped -> if (event.tapped) "tap" else "untap"
        is GameEvent.DamageDealtToCard -> "damage_to_creature"
        is GameEvent.DamageDealtToPlayer -> if (event.combat) "combat_damage" else "noncombat_damage"
        is GameEvent.LifeChanged -> "life_change"
        is GameEvent.AttackersDeclared -> "attackers_declared"
        is GameEvent.BlockersDeclared -> "blockers_declared"
        is GameEvent.LegendRuleDeath -> "legend_rule"
        is GameEvent.CardDestroyed -> "destroy"
        is GameEvent.CardSacrificed -> "sacrifice"
        is GameEvent.CardBounced -> "bounce"
        is GameEvent.CardExiled -> "exile"
        is GameEvent.CardDiscarded -> "discard"
        is GameEvent.CardMilled -> "mill"
        is GameEvent.CardSurveiled -> "surveil_card"
        is GameEvent.SpellCountered -> "counter"
        is GameEvent.TokenCreated -> "token_create"
        is GameEvent.TokenDestroyed -> "token_destroy"
        is GameEvent.CountersChanged -> "counters_change"
        is GameEvent.LibraryShuffled -> "shuffle"
        is GameEvent.Scry -> "scry"
        is GameEvent.Surveil -> "surveil"
        is GameEvent.CardAttached -> "attachment"
        is GameEvent.CardDetached -> "detachment"
        is GameEvent.CardsRevealed -> "reveal"
        is GameEvent.CombatEnded -> "combat_end"
        is GameEvent.PowerToughnessChanged -> "pt_change"
        is GameEvent.PhaseChanged -> "phase_change"
        is GameEvent.ManaAbilityActivated -> "mana_ability"
        is GameEvent.SpellMovedToStack -> "spell_moved_to_stack"
    }

    /**
     * Classify a stream of events and return sorted mechanic counts.
     */
    fun classifyAll(events: List<GameEvent>): List<MechanicCount> =
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
