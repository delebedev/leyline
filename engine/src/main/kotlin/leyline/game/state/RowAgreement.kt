package leyline.game.state

import forge.game.spellability.SpellAbility
import leyline.bridge.types.AbilityIdentitySource
import leyline.game.data.CardRepository
import leyline.game.data.catalogKeywords
import leyline.game.data.normalizedKeyword

/**
 * Checks that a resolved catalog row describes the Forge ability it was resolved for.
 *
 * An intrinsic keyword ability must resolve to a row the catalog names for the same keyword. A trigger must not
 * resolve to an activated row, and an activated ability must not resolve to a triggered row. A delayed trigger takes
 * the row of the ability that created it, such as Earthbend's return trigger taking the Earthbend activation's row.
 * Its kind is not checked when [SPAWNED_ROW_SOURCES] answered: the spawning ability itself, or the row recorded
 * from it (or from the mode chosen for it) when the trigger was first pending. Rows the catalog says nothing about
 * agree with any ability.
 */
class RowAgreement(
    private val repository: CardRepository,
) {
    /** A description of the disagreement, or null when the row agrees with [ability]. */
    fun disagreement(
        ability: SpellAbility,
        row: Int,
        via: AbilityIdentitySource? = null,
    ): String? {
        if (row <= 0 || via in SPAWNED_ROW_SOURCES) return null
        val keyword = ability.keyword ?: ability.trigger?.keyword
        if (keyword != null) {
            if (!keyword.isIntrinsic) return null
            val named = repository.catalogKeywords(row)
            val expected = keyword.keyword.toString()
            return if (named.isEmpty() || normalizedKeyword(expected) in named) null else "$expected keyword on row $row"
        }
        val expected =
            when {
                ability.trigger != null -> TRIGGER_CATEGORY
                ability.isActivatedAbility -> ACTIVATED_CATEGORY
                else -> return null
            }
        val category = repository.findAbilityInfo(row)?.category ?: return null
        if (category !in CHECKED_CATEGORIES || category == expected) return null
        return "${kindName(expected)} on ${kindName(category)} row $row"
    }

    private fun kindName(category: Int): String = if (category == TRIGGER_CATEGORY) "trigger" else "activated ability"

    private companion object {
        const val ACTIVATED_CATEGORY = 1
        const val TRIGGER_CATEGORY = AbilityRegistry.TRIGGER_CATEGORY
        val CHECKED_CATEGORIES = setOf(ACTIVATED_CATEGORY, TRIGGER_CATEGORY)
        val SPAWNED_ROW_SOURCES = setOf(AbilityIdentitySource.SpawningAbility, AbilityIdentitySource.PendingTrigger)
    }
}
