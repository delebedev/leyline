package leyline.game.data

import forge.game.card.Card
import leyline.game.codes.SlotEntry
import leyline.game.codes.SlotKind
import leyline.game.codes.SlotLayout
import java.util.concurrent.atomic.AtomicInteger

/**
 * Derives synthetic abilityGrpId lists from Forge card objects.
 *
 * Shared logic between [PuzzleCardRegistrar] (main) and CardDataDeriver (test).
 * Each caller provides its own [AtomicInteger] counter for ID allocation.
 */
object AbilityIdDeriver {
    /** Well-known ability IDs for basic land mana abilities. */
    val BASIC_LAND_ABILITIES =
        listOf(
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
        /**
         * Per-chapter ability grpIds for Saga cards, indexed 0-based (chapter I at [0]).
         * Empty for non-saga cards. These grpIds are also included in [abilityIds] and
         * [slotLayout] as trailing slots — present in both places so the keyword/activated
         * slot contract required by ActionMapper stays intact.
         */
        val chapterAbilityGrpIds: List<Int> = emptyList(),
        /**
         * Per-slot kind aligned 1:1 with [abilityIds]. Mirrors `slotLayout.slots`
         * but exposed as a flat list for [CardData.abilityKinds] consumers.
         */
        val abilityKinds: List<SlotKind> = emptyList(),
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
                    slotLayout =
                        SlotLayout(
                            keywordCount = 0,
                            activatedCount = 0,
                            slots = listOf(SlotEntry(abilityId, 0, SlotKind.Mana)),
                        ),
                    abilityKinds = listOf(SlotKind.Mana),
                )
            }
        }

        val keywords =
            card.rules
                ?.mainPart
                ?.keywords
                ?.toList() ?: emptyList()
        // Count non-mana activated abilities — matches the filter in ActionMapper and CardLookup.
        val activatedCount = card.spellAbilities?.count { it.isActivatedAbility && !it.isManaAbility() } ?: 0

        // Saga chapter triggers: one grpId per chapter number, 1..FinalChapterNr.
        // De-dup in case Forge registers multiple triggers under the same chapter.
        val chapterNumbers = deriveChapterNumbers(card)
        val chapterCount = chapterNumbers.size

        val totalCount = maxOf(1, keywords.size + activatedCount + chapterCount)

        val abilityIds = (0 until totalCount).map { counter.getAndIncrement() to 0 }

        val slotEntries = mutableListOf<SlotEntry>()
        val keywordMap = mutableMapOf<String, Int>()
        val chapterGrpIds = mutableListOf<Int>()

        // Preserve the existing keyword-then-activated contract (ActionMapper depends on it),
        // then append chapter slots. Chapter lookup is by number, not by position, so the
        // trailing placement is fine — ZoneMapper.resolveChapterAbilityGrpId reads from
        // CardData.chapterAbilityGrpIds which we populate below.
        for ((i, kw) in keywords.withIndex()) {
            if (i < abilityIds.size) {
                keywordMap[kw.uppercase()] = abilityIds[i].first
                slotEntries.add(SlotEntry(abilityIds[i].first, 0, SlotKind.Keyword))
            }
        }
        for (i in keywords.size until keywords.size + activatedCount) {
            slotEntries.add(SlotEntry(abilityIds[i].first, 0, SlotKind.Activated))
        }
        val chapterSlotStart = keywords.size + activatedCount
        for ((i, _) in chapterNumbers.withIndex()) {
            val grpId = abilityIds[chapterSlotStart + i].first
            slotEntries.add(SlotEntry(grpId, 0, SlotKind.Activated))
            chapterGrpIds.add(grpId)
        }

        return DerivedAbilities(
            abilityIds = abilityIds,
            keywordAbilityGrpIds = keywordMap,
            slotLayout =
                SlotLayout(
                    keywordCount = keywords.size,
                    activatedCount = activatedCount + chapterCount,
                    slots = slotEntries,
                ),
            chapterAbilityGrpIds = chapterGrpIds,
            abilityKinds = slotEntries.map { it.kind },
        )
    }

    /**
     * Return distinct chapter numbers declared by this card's triggers, in ascending
     * order. For Tribute to Horobi (K:Chapter:3:...) → [1, 2, 3]. Empty for non-sagas.
     */
    private fun deriveChapterNumbers(card: Card): List<Int> {
        val state = card.currentState ?: return emptyList()
        return state.triggers
            .asSequence()
            .filter { it.isChapter }
            .mapNotNull { it.chapter }
            .distinct()
            .sorted()
            .toList()
    }
}
