package leyline.game.state

import forge.game.keyword.KeywordInterface
import leyline.game.data.normalizedKeyword

/** Binds intrinsic keyword declarations to the catalog rows that hold them. */
internal object KeywordRowSlots {
    /**
     * Catalog row index for each bound declaration: by keyword definition first, then by unclaimed position. The
     * positional pass skips a row whose [categories] entry is a different kind than the keyword's abilities, such
     * as a printed trigger row in front of Cycling.
     */
    fun bind(
        bindings: Map<Int, KeywordInterface>,
        abilityIds: List<Pair<Int, Int>>,
        rowKeywords: (Int) -> Set<String>,
        categories: List<Int>?,
    ): Map<Int, Int> {
        val named = abilityIds.map { rowKeywords(it.first) }
        val slots = mutableMapOf<Int, Int>()
        for ((declaration, keyword) in bindings.toSortedMap()) {
            val name = normalizedKeyword(keyword.keyword.toString())
            abilityIds.indices
                .firstOrNull { it !in slots.values && name in named[it] }
                ?.let { slots[declaration] = it }
        }
        for (declaration in bindings.keys.sorted()) {
            if (declaration in slots || declaration in slots.values || named[declaration].isNotEmpty()) continue
            if (categories != null && !categoryFits(bindings.getValue(declaration), categories[declaration])) continue
            slots[declaration] = declaration
        }
        return slots
    }

    /**
     * Whether a row of [category] can hold [keyword]: activated rows for activations, trigger rows for triggers,
     * static rows for statics. Other categories, such as keyword and alternative cost rows, hold any keyword.
     */
    private fun categoryFits(
        keyword: KeywordInterface,
        category: Int,
    ): Boolean {
        val abilities = keyword.abilities.toList()
        return when {
            category !in ACTIVATED_CATEGORY..AbilityRegistry.STATIC_CATEGORY_FLOOR -> true
            abilities.isNotEmpty() && abilities.all { it.isActivatedAbility } -> category == ACTIVATED_CATEGORY
            abilities.isNotEmpty() -> true
            keyword.triggers.toList().isNotEmpty() -> category == AbilityRegistry.TRIGGER_CATEGORY
            keyword.staticAbilities.toList().isNotEmpty() -> category == AbilityRegistry.STATIC_CATEGORY_FLOOR
            else -> true
        }
    }

    private const val ACTIVATED_CATEGORY = 1
}
