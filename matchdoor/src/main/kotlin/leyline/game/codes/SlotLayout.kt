package leyline.game.codes

/**
 * Single source of truth for the ability slot layout of a card.
 *
 * Produced by [leyline.game.data.AbilityIdDeriver] (for synthetic cards) and [leyline.game.state.AbilityRegistry.build]
 * (for all cards at runtime). Consumed by `MatchSession.resolveAbilityIndex`.
 * Eliminates the dual-derivation bug class where keyword count was computed
 * independently in two places.
 *
 * Slot ordering matches the source `Cards.AbilityIds` array verbatim. Slots
 * may interleave kinds (e.g. an intrinsic trigger at slot 0 followed by
 * activated abilities). Use [forgeIndexFor] for grpId → Forge-index lookup;
 * do not derive Forge index from slot position arithmetic.
 *
 * Counts ([keywordCount], [activatedCount]) are totals, not ranges — they
 * don't imply contiguous occupancy.
 */
data class SlotLayout(
    val keywordCount: Int,
    val activatedCount: Int,
    val slots: List<SlotEntry>,
) {
    /**
     * Map an Arena abilityGrpId to its Forge ability index.
     *
     * Returns the index into the Forge-order non-mana activated abilities
     * (i.e., what `getNonManaActivatedAbilities` returns) — counting only
     * [SlotKind.Activated] slots, not raw slot position. This matches the
     * dispatch contract: `SpellExecutor.activateAbility(cardId, index, ...)`
     * indexes into the Forge-order activated list, which excludes triggers
     * and statics.
     *
     * - [SlotKind.Activated] slots return `>= 0` (the Forge ability index)
     * - [SlotKind.Keyword] slots return negative values (signals "this is a keyword")
     * - [SlotKind.Mana] slots return `0` (mana is resolved separately, never reaches index dispatch)
     * - [SlotKind.Intrinsic] slots return `null` (triggers/statics are not activatable)
     * - Unknown abilityGrpIds return `null`
     */
    fun forgeIndexFor(abilityGrpId: Int): Int? {
        val slot = slots.indexOfFirst { it.abilityGrpId == abilityGrpId }
        if (slot < 0) return null
        return when (slots[slot].kind) {
            SlotKind.Keyword -> slot - keywordCount
            SlotKind.Activated -> slots.take(slot).count { it.kind == SlotKind.Activated }
            SlotKind.Mana -> 0
            SlotKind.Intrinsic -> null
        }
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
