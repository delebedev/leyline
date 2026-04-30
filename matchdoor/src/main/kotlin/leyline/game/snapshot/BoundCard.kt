package leyline.game.snapshot

import leyline.bridge.types.ForgeCardId
import leyline.game.data.CardData
import leyline.game.data.CardRepository
import leyline.game.data.KeywordAbilityIds
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Per-frame bound view of one card — pairs the live [CardSnapshot] with the
 * static [CardData] resolved at snapshot time, plus pre-resolved alt-cost
 * bindings so consumers don't reach back into `bridge.cardRepository`.
 *
 * The cornerstone of the architectural-pressure epic (S2.A in `leyline-y3pf`):
 * as phases land, more pre-resolved fields move onto BoundCard (modal options,
 * mobilize cleanup, parent linkage, designations) and `CardSnapshot` shrinks
 * until Phase 7 retires it entirely.
 *
 * Field set today:
 *  - [snapshot] holds the live state (zone-affecting fields, P/T, designations)
 *  - [data] holds the static metadata (null when no DB row exists)
 *  - [altCosts] enumerates the BaseId-chain alt-cost ability rows on the card
 *    (Plot, Foretell, Disturb, Escape, Warp, Sneak, Madness, Flashback,
 *    Mobilize) — the data driving [leyline.game.mapping.ActionMapper]'s
 *    cast-from-non-hand-zone and hand-alt-cost rails.
 */
data class BoundCard(
    val forgeCardId: ForgeCardId,
    val snapshot: CardSnapshot,
    val data: CardData?,
    val altCosts: List<AltCostBinding> = emptyList(),
) {
    /**
     * Find the alt-cost row whose [AltCostBinding.keywordBaseId] matches
     * [keywordBaseId]. Cost-agnostic — useful when a card has at most one
     * row for the keyword (Foretell, Disturb, Escape, Plot's exile-cast leg).
     */
    fun altCost(keywordBaseId: Int): AltCostBinding? = altCosts.firstOrNull { it.keywordBaseId == keywordBaseId }

    /**
     * Find the alt-cost row whose [AltCostBinding.keywordBaseId] matches
     * [keywordBaseId] AND whose [AltCostBinding.manaCost] equals [manaCost]
     * (multiset-equal). Cost-aware — needed when multiple rows share a base
     * (Warp / Sneak shared ability rows across cards) and the consumer's
     * SA cost picks one.
     */
    fun altCostFor(
        keywordBaseId: Int,
        manaCost: List<Pair<ManaColor, Int>>,
    ): AltCostBinding? =
        altCosts.firstOrNull {
            it.keywordBaseId == keywordBaseId && it.manaCost.toMap() == manaCost.toMap()
        }

    companion object {
        /**
         * BaseId chain roots that identify alt-cost ability rows on a card.
         * Each appears as the `BaseId` of at most one ability row per printing
         * (Warp/Sneak/Plot/Foretell/Disturb/Escape/Madness/Flashback/Mobilize).
         */
        private val ALT_COST_BASE_IDS: Set<Int> =
            setOf(
                KeywordAbilityIds.FLASHBACK,
                KeywordAbilityIds.MADNESS,
                KeywordAbilityIds.ESCAPE,
                KeywordAbilityIds.FORETELL,
                KeywordAbilityIds.DISTURB,
                KeywordAbilityIds.PLOT,
                KeywordAbilityIds.MOBILIZE,
                KeywordAbilityIds.WARP,
                KeywordAbilityIds.SNEAK,
            )

        /**
         * Walk [data]'s `abilityIds` once and return the alt-cost rows whose
         * [leyline.game.data.AbilityInfo.baseId] is one of the well-known
         * keyword roots ([ALT_COST_BASE_IDS]). Single source of truth for the
         * "what alt-cost rails does this card carry" question — production
         * snapshot binding and the deprecated live-Forge action path both
         * call here so the lookup never recurs at consumer sites.
         */
        fun bindAltCosts(
            data: CardData?,
            repo: CardRepository,
        ): List<AltCostBinding> {
            if (data == null) return emptyList()
            val out = mutableListOf<AltCostBinding>()
            for ((abilityGrpId, _) in data.abilityIds) {
                val info = repo.findAbilityInfo(abilityGrpId) ?: continue
                if (info.baseId !in ALT_COST_BASE_IDS) continue
                out +=
                    AltCostBinding(
                        keywordBaseId = info.baseId,
                        abilityGrpId = abilityGrpId,
                        manaCost = info.manaCost,
                    )
            }
            return out
        }
    }
}

/**
 * One alt-cost ability row on a card — the (keyword-base, per-printing
 * abilityGrpId, mana cost) triple that ActionMapper's cast rails consume.
 *
 * Pre-resolved at snapshot time so consumers don't re-walk
 * [CardData.abilityIds] / re-call `findKeywordAbilityGrpId` /
 * `findAlternativeCostAbilityGrpId` at action-emission time.
 */
data class AltCostBinding(
    /** Keyword's BaseId (one of [KeywordAbilityIds]'s alt-cost constants). */
    val keywordBaseId: Int,
    /** Per-card ability grpId for this keyword's row in `Cards.AbilityIds`. */
    val abilityGrpId: Int,
    /** Cast cost on this row (multiset-keyed for cost-aware disambiguation). */
    val manaCost: List<Pair<ManaColor, Int>>,
)
