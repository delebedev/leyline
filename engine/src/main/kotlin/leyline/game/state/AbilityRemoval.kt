package leyline.game.state

import forge.game.card.Card
import forge.game.card.CardTraitChanges
import forge.game.keyword.Keyword
import forge.game.zone.ZoneType
import leyline.bridge.types.ForgeCardId
import leyline.game.codes.KeywordGrpIds
import leyline.game.data.CardRepository

/**
 * One continuous effect removing abilities from a permanent, keyed by Forge's layer-6 cell.
 *
 * Forge records a removal as a remove predicate on `changedCardTraits` and a remove-all flag or
 * removed keyword list on `changedCardKeywords`, both under the same (timestamp, staticId) cell.
 * `staticId` is zero for an effect created by a resolving ability; otherwise [sourceForgeCardId]
 * is the permanent whose static ability applies the removal.
 */
data class AbilityRemoval(
    val timestamp: Long,
    val staticId: Long,
    val scope: Scope,
    val sourceForgeCardId: ForgeCardId? = null,
    /** Printed and component ability rows of the permanent that this removal hides. */
    val removedAbilityGrpIds: List<Int> = emptyList(),
    /** The removed keywords also can't be had or gained (CR 113.11), so grants of any timestamp are hidden. */
    val cantHave: Boolean = false,
) {
    sealed interface Scope {
        /** Every ability; the wire value is `"all"`. */
        data object All : Scope

        /** Every ability except mana abilities. */
        data object NonMana : Scope

        /** Named keywords only, by Forge keyword name. */
        data class Keywords(
            val names: List<String>,
        ) : Scope
    }

    /** Whether this removal suppresses an ability granted by a keyword effect at [grantTimestamp]. */
    fun suppressesGrant(
        grantTimestamp: Long,
        keyword: String?,
    ): Boolean =
        when (scope) {
            Scope.All, Scope.NonMana -> grantTimestamp < timestamp
            is Scope.Keywords ->
                keyword != null && scope.names.any { sameKeyword(it, keyword) } && (cantHave || grantTimestamp < timestamp)
        }

    /** This removal with the rows it hides among the permanent's printed and component [rows]. */
    fun resolvedAgainst(
        rows: List<Int>,
        repository: CardRepository,
    ): AbilityRemoval = copy(removedAbilityGrpIds = removedRows(rows, repository).distinct())

    /** Printed or component ability rows this removal hides from [rows]. */
    private fun removedRows(
        rows: List<Int>,
        repository: CardRepository,
    ): List<Int> =
        when (scope) {
            Scope.All -> rows
            Scope.NonMana -> rows.filterNot { repository.findAbilityInfo(it)?.subCategory == MANA_SUB_CATEGORY }
            is Scope.Keywords ->
                rows.filter { row ->
                    scope.names.any { name ->
                        repository.findAbilityLocalization(row)?.keyword?.let { sameKeyword(it, name) } == true ||
                            KeywordGrpIds.forKeyword(name) == row
                    }
                }
        }

    companion object {
        /** Catalog marker for a mana ability row. */
        private const val MANA_SUB_CATEGORY = 1

        /** Current removals on [card], in timestamp order. */
        fun read(card: Card): List<AbilityRemoval> {
            val traits = card.changedCardTraits
            val keywords = card.changedCardKeywords
            val cantHaveNames = card.cantHaveKeyword.map { it.toString() }.toSet()
            val cells =
                (
                    traits.cellSet().filter { (it.value as? CardTraitChanges)?.remove() != null } +
                        keywords.cellSet().filter { it.value.isRemoveAllKeywords || it.value.removeKeywords.isNotEmpty() }
                ).map { it.rowKey to it.columnKey }
                    .distinct()
                    .sortedWith(compareBy({ it.first }, { it.second }))
            return cells.mapNotNull { (timestamp, staticId) ->
                val removeTraits = (traits.get(timestamp, staticId) as? CardTraitChanges)?.remove()
                val keywordChange = keywords.get(timestamp, staticId)
                val scope =
                    when {
                        keywordChange?.isRemoveAllKeywords == true && removeTraits != null ->
                            if (card.currentState.manaAbilities.all(removeTraits::test)) Scope.All else Scope.NonMana
                        keywordChange?.removeKeywords?.isNotEmpty() == true ->
                            Scope.Keywords(keywordChange.removeKeywords.map { Keyword.getInstance(it).keyword.toString() })
                        else -> null
                    }
                scope?.let {
                    AbilityRemoval(
                        timestamp = timestamp,
                        staticId = staticId,
                        scope = it,
                        sourceForgeCardId = staticHost(card, staticId),
                        cantHave = it is Scope.Keywords && it.names.all { name -> name in cantHaveNames },
                    )
                }
            }
        }

        private fun staticHost(
            card: Card,
            staticId: Long,
        ): ForgeCardId? =
            staticId.takeIf { it > 0 }?.let { id ->
                card.game
                    .getCardsIn(ZoneType.Battlefield)
                    .firstOrNull { host -> host.staticAbilities.any { it.id.toLong() == id } }
                    ?.let { ForgeCardId(it.id) }
            }

        private fun sameKeyword(
            a: String,
            b: String,
        ): Boolean = a.equals(b, ignoreCase = true)
    }
}
