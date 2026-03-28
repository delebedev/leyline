package leyline.game

/**
 * Single source of truth for the ability slot layout of a card.
 *
 * Produced by [AbilityIdDeriver], consumed by [AbilityRegistry] and
 * `MatchSession.resolveAbilityIndex`. Eliminates the dual-derivation
 * bug class where keyword count was computed independently in two places.
 *
 * Slot ordering: keywords occupy slots `[0, keywordCount)`,
 * activated abilities occupy `[keywordCount, keywordCount + activatedCount)`.
 */
data class SlotLayout(
    val keywordCount: Int,
    val activatedCount: Int,
    val slots: List<SlotEntry>,
) {
    /**
     * Map an Arena abilityGrpId to its Forge ability index.
     *
     * Returns the slot index minus keyword count:
     * - Activated abilities return `>= 0` (the Forge ability index)
     * - Keywords return negative values (not activated abilities)
     * - Unknown abilityGrpIds return `null`
     */
    fun forgeIndexFor(abilityGrpId: Int): Int? {
        val slot = slots.indexOfFirst { it.abilityGrpId == abilityGrpId }
        if (slot < 0) return null
        return slot - keywordCount
    }

    /** The abilityIds pairs for CardData (grpId to textId). */
    fun toAbilityIdPairs(): List<Pair<Int, Int>> = slots.map { it.abilityGrpId to it.textId }

    companion object {
        val EMPTY = SlotLayout(keywordCount = 0, activatedCount = 0, slots = emptyList())
    }
}

data class SlotEntry(
    val abilityGrpId: Int,
    val textId: Int,
    val kind: SlotKind,
)

enum class SlotKind { Keyword, Activated, Mana, Intrinsic }
