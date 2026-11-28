package leyline.game.state

import leyline.bridge.types.AbilityLookup
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts how ability identity lookups ended during one match, keyed by lookup site and by the source that
 * answered or the reason none did.
 *
 * Counts are per call: a projection that looks the same ability up twice counts twice. A site that delegates
 * to another site counts both, under each site's own key.
 */
class AbilityIdentityLedger {
    /** Where an identity lookup happens. */
    enum class Site(
        val label: String,
    ) {
        /** A spell or activated ability through its source card's registry. */
        SpellAbility("spell-ability"),

        /** A trigger through its source card's registry and the trigger fallbacks. */
        Trigger("trigger"),

        /** A bare definition reference through its source card's registry. */
        Definition("definition"),

        /** A prompt's ability, including the modal choice recorded for its source. */
        Prompt("prompt"),

        /** A stack entry's ability row. */
        StackEntry("stack-entry"),

        /** A keyword granted by a static, through the granting card's catalog. */
        GrantedKeyword("granted-keyword"),

        /** A cast offer's alternative cost row. */
        CastRail("cast-rail"),

        /** An activated offer's row and occurrence. */
        Activated("activated"),

        /** A granted keyword's row read while translating a Forge game event. */
        Event("event"),

        /** The alternative cost row the copilot expects for a cast it is about to choose. */
        CopilotCast("copilot-cast"),

        /** A replacement option's keyword row and occurrence. */
        Replacement("replacement"),

        /** An exhausted ability's row. */
        Exhaustion("exhaustion"),

        /** The row a delayed trigger takes from the ability that created it. */
        SpawningTrigger("spawning-trigger"),
    }

    private val resolved = ConcurrentHashMap<String, AtomicInteger>()
    private val missed = ConcurrentHashMap<String, AtomicInteger>()

    fun <T> record(
        site: Site,
        lookup: AbilityLookup<T>,
    ): AbilityLookup<T> {
        when (lookup) {
            is AbilityLookup.Resolved -> increment(resolved, "${site.label}/${lookup.via.label}")
            is AbilityLookup.Missing -> increment(missed, "${site.label}/${lookup.reason.label}")
        }
        return lookup
    }

    /** Resolved lookups keyed `site/source`. */
    fun resolvedBySource(): Map<String, Int> = resolved.snapshot()

    /** Missed lookups keyed `site/reason`. */
    fun missedByReason(): Map<String, Int> = missed.snapshot()

    private fun increment(
        counts: ConcurrentHashMap<String, AtomicInteger>,
        key: String,
    ) {
        counts.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
    }

    private fun ConcurrentHashMap<String, AtomicInteger>.snapshot(): Map<String, Int> = mapValues { it.value.get() }.toSortedMap()
}
