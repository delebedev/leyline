package leyline.game

/**
 * Single source of truth for the ability slot layout of a card.
 *
 * Produced by [AbilityIdDeriver] (for synthetic cards) and [AbilityRegistry.build]
 * (for all cards at runtime). Consumed by `MatchSession.resolveAbilityIndex`.
 * Eliminates the dual-derivation bug class where keyword count was computed
 * independently in two places.
 *
 * Slot ordering: keywords `[0, keywordCount)`, then triggers (Intrinsic),
 * then activated abilities `[keywordCount + triggerCount, ...)`.
 */
data class SlotLayout(
    val keywordCount: Int,
    val activatedCount: Int,
    val slots: List<SlotEntry>,
) {
    /**
     * Map an Arena abilityGrpId to its Forge ability index.
     *
     * Counts only [SlotKind.Activated] slots preceding the target to produce
     * the Forge-side ability index (keywords and triggers don't count).
     * Returns negative for non-activated slots, null for unknown abilityGrpIds.
     */
    fun forgeIndexFor(abilityGrpId: Int): Int? {
        val slot = slots.indexOfFirst { it.abilityGrpId == abilityGrpId }
        if (slot < 0) return null
        if (slots[slot].kind != SlotKind.Activated) {
            // Non-activated: return negative offset (same contract as before)
            return slot - keywordCount - slots.take(slot + 1).count { it.kind == SlotKind.Intrinsic }
        }
        // Count activated slots before this one — this IS the Forge ability index
        return slots.take(slot).count { it.kind == SlotKind.Activated }
    }

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
