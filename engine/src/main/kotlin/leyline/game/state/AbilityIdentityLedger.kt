package leyline.game.state

import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.IndistinguishableOffer
import leyline.bridge.handoff.forgeAbility
import leyline.bridge.types.AbilityIdentitySource
import leyline.bridge.types.AbilityLookup
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts how ability identity lookups ended during one match, keyed by lookup site and by the source that
 * answered or the reason none did.
 *
 * Counts are per call: a projection that looks the same ability up twice counts twice. A site that delegates
 * to another site counts both, under each site's own key.
 */
class AbilityIdentityLedger(
    private val rowAgreement: RowAgreement? = null,
) {
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

        /** An intrinsic keyword declaration's catalog row while a registry is built. */
        KeywordSlot("keyword-slot"),

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
    private val mismatches = ConcurrentHashMap<String, AtomicInteger>()
    private val ambiguousCatalogs = ConcurrentHashMap<String, AtomicInteger>()

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

    /** Count a resolved [row] that does not describe [ability]; see [RowAgreement]. */
    fun checkRow(
        site: Site,
        ability: SpellAbility,
        row: Int,
        via: AbilityIdentitySource? = null,
    ) {
        val disagreement = rowAgreement?.disagreement(ability, row, via) ?: return
        val source = ability.hostCard?.name ?: "unknown source"
        if (increment(mismatches, "${site.label}/$source: $disagreement") == 1) {
            log.warn("Ability row disagrees with its Forge ability at {}: {}: {}", site.label, source, disagreement)
        }
    }

    /**
     * Count each offer dropped because its action equals an earlier offer's for a different Forge ability. The key
     * names the action, the dropped command and its ability. A pair that differs in definition or cost is logged at
     * error, because the client then cannot choose the cost or ability it would get from the dropped offer.
     */
    fun recordIndistinguishableOffers(dropped: List<IndistinguishableOffer>) {
        for ((kept, offer) in dropped) {
            val action = offer.action
            val ability = offer.forgeAbility()
            val key =
                "${action.actionType.name} grpId=${action.grpId} abilityGrpId=${action.abilityGrpId} " +
                    "uniqueAbilityId=${action.uniqueAbilityId} dropped=${offer.command::class.simpleName} " +
                    "\"${ability?.description.orEmpty()}\""
            if (increment(ambiguousCatalogs, key) > 1) continue
            val keptAbility = kept.forgeAbility()
            val differs =
                keptAbility?.definitionId != ability?.definitionId ||
                    keptAbility?.payCosts?.toSimpleString() != ability?.payCosts?.toSimpleString()
            if (differs) {
                log.error("Action catalog dropped an indistinguishable offer with a different definition or cost: {}", key)
            } else {
                log.warn("Action catalog dropped an indistinguishable offer; keeping the first: {}", key)
            }
        }
    }

    /** Resolved lookups keyed `site/source`. */
    fun resolvedBySource(): Map<String, Int> = resolved.snapshot()

    /** Missed lookups keyed `site/reason`. */
    fun missedByReason(): Map<String, Int> = missed.snapshot()

    /** Resolved rows that disagree with their Forge ability, keyed `site/card: disagreement`. */
    fun rowMismatches(): Map<String, Int> = mismatches.snapshot()

    /** Indistinguishable offered actions, keyed by action shape. */
    fun ambiguousCatalogs(): Map<String, Int> = ambiguousCatalogs.snapshot()

    private fun increment(
        counts: ConcurrentHashMap<String, AtomicInteger>,
        key: String,
    ): Int = counts.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()

    private fun ConcurrentHashMap<String, AtomicInteger>.snapshot(): Map<String, Int> = mapValues { it.value.get() }.toSortedMap()
}

private val log = LoggerFactory.getLogger(AbilityIdentityLedger::class.java)
