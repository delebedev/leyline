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
     * Derived ability data including abilityIds, keywordAbilityGrpIds, and [SlotLayout].
     *
     * [slotLayout] is the single source of truth for slot ordering — keywords occupy
     * the first N slots, then non-mana activated abilities. Callers should prefer
     * [slotLayout] over recomputing keyword/activated counts independently.
     */
    data class DerivedAbilities(
        val abilityIds: List<Pair<Int, Int>>,
        val keywordAbilityGrpIds: Map<String, Int>,
        val slotLayout: SlotLayout,
    )

    /**
     * Derive [DerivedAbilities] for a card.
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
    ): DerivedAbilities {
        // Basic lands get well-known ability IDs
        val subtypes = card.type.subtypes.map { it.lowercase() }
        for ((subtype, abilityId) in BASIC_LAND_ABILITIES) {
            if (subtype in subtypes) {
                return DerivedAbilities(
                    abilityIds = listOf(abilityId to 0),
                    keywordAbilityGrpIds = emptyMap(),
                    slotLayout = SlotLayout(
                        keywordCount = 0,
                        activatedCount = 0,
                        slots = listOf(SlotEntry(abilityId, 0, SlotKind.Mana)),
                    ),
                )
            }
        }

        val keywords = card.rules?.mainPart?.keywords?.toList() ?: emptyList()
        // Count non-mana activated abilities — matches the filter in ActionMapper and CardLookup.
        val activatedCount = card.spellAbilities?.count { it.isActivatedAbility && !it.isManaAbility() } ?: 0
        val totalCount = maxOf(1, keywords.size + activatedCount)

        val abilityIds = (0 until totalCount).map { counter.getAndIncrement() to 0 }

        val slotEntries = mutableListOf<SlotEntry>()
        val keywordMap = mutableMapOf<String, Int>()

        for ((i, kw) in keywords.withIndex()) {
            if (i < abilityIds.size) {
                keywordMap[kw.uppercase()] = abilityIds[i].first
                slotEntries.add(SlotEntry(abilityIds[i].first, 0, SlotKind.Keyword))
            }
        }
        for (i in keywords.size until totalCount) {
            slotEntries.add(SlotEntry(abilityIds[i].first, 0, SlotKind.Activated))
        }

        return DerivedAbilities(
            abilityIds = abilityIds,
            keywordAbilityGrpIds = keywordMap,
            slotLayout = SlotLayout(
                keywordCount = keywords.size,
                activatedCount = activatedCount,
                slots = slotEntries,
            ),
        )
    }
}
