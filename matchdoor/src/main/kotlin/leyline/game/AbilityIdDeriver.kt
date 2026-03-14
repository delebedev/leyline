package leyline.game

import forge.game.card.Card
import java.util.concurrent.atomic.AtomicInteger

/**
 * Derives synthetic abilityGrpId lists from Forge card objects.
 *
 * Shared logic between [PuzzleCardRegistrar] (main) and CardDataDeriver (test).
 * Each caller provides its own [AtomicInteger] counter for ID allocation.
 */
object AbilityIdDeriver {

    /** Well-known ability IDs for basic land mana abilities. */
    val BASIC_LAND_ABILITIES = listOf(
        "plains" to 1001,
        "island" to 1002,
        "swamp" to 1003,
        "mountain" to 1004,
        "forest" to 1005,
    )

    /**
     * Derive (abilityIds, keywordAbilityGrpIds) for a card.
     *
     * Layout: keywords occupy the first N slots, then non-mana activated abilities.
     * This ordering must match [ActionMapper]'s iteration and [MatchSession]'s
     * reverse lookup in [resolveAbilityIndex].
     *
     * @param card live Forge card object
     * @param counter AtomicInteger for allocating synthetic abilityGrpIds
     */
    fun deriveAbilityIds(
        card: Card,
        counter: AtomicInteger,
    ): Pair<List<Pair<Int, Int>>, Map<String, Int>> {
        // Basic lands get well-known ability IDs
        val subtypes = card.type.subtypes.map { it.lowercase() }
        for ((subtype, abilityId) in BASIC_LAND_ABILITIES) {
            if (subtype in subtypes) return listOf(abilityId to 0) to emptyMap()
        }

        val keywords = card.rules?.mainPart?.keywords?.toList() ?: emptyList()
        // Count non-mana activated abilities — matches the filter in ActionMapper and CardLookup.
        val activatedCount = card.spellAbilities?.count { it.isActivatedAbility && !it.isManaAbility() } ?: 0
        val totalCount = maxOf(1, keywords.size + activatedCount)

        val abilityIds = (0 until totalCount).map { counter.getAndIncrement() to 0 }

        // Keywords occupy the first slots
        val keywordMap = mutableMapOf<String, Int>()
        for ((i, kw) in keywords.withIndex()) {
            if (i < abilityIds.size) {
                keywordMap[kw.uppercase()] = abilityIds[i].first
            }
        }
        return abilityIds to keywordMap
    }
}
