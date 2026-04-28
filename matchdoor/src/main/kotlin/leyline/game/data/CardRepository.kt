package leyline.game.data

import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import kotlin.collections.iterator

/**
 * Read-only card data repository — abstracts the client's local card database.
 *
 * Production impl ([ExposedCardRepository]) reads from the client's SQLite.
 * Tests use `InMemoryCardRepository` (in test source set) with synthetic data.
 */
interface CardRepository {
    fun findByGrpId(grpId: Int): CardData?

    fun findNameByGrpId(grpId: Int): String?

    fun findGrpIdByName(name: String): Int?

    /** Like [findGrpIdByName] but includes secondary faces (adventure, DFC back). */
    fun findGrpIdByNameAnyFace(name: String): Int? = findGrpIdByName(name)

    fun findGrpIdByNameAndSet(
        name: String,
        setCode: String,
    ): Int? = findGrpIdByName(name)

    /** All non-token, primary-card grpIds in the database. */
    fun findAllGrpIds(): List<Int>

    /**
     * Look up modal options for a card. Returns the parent ability grpId
     * and list of child option grpIds from the Abilities table's ModalChildIds column.
     * Returns null if the card has no modal abilities.
     */
    fun lookupModalOptions(cardGrpId: Int): ModalAbilityInfo? = null

    /**
     * Register modal options for testing (no DB needed).
     */
    fun registerModalOptions(
        cardGrpId: Int,
        info: ModalAbilityInfo,
    ) {}

    /** Linked face grpIds for multi-face cards (DFC, MDFC, Adventure, Split). */
    fun findLinkedFaces(grpId: Int): List<Int> = findByGrpId(grpId)?.linkedFaceGrpIds ?: emptyList()

    /**
     * Look up a single ability row from the client's Abilities table.
     *
     * Returns null if the repository does not carry Abilities-table data
     * (in-memory test repos) or the row is absent. Production impl is
     * [ExposedCardRepository] which reads the client SQLite.
     */
    fun findAbilityInfo(abilityGrpId: Int): AbilityInfo? = null

    /**
     * Cost-disambiguated alt-cost lookup: find the ability on [cardGrpId]
     * whose `BaseId` matches [keywordBaseId] AND whose `OldSchoolManaText`
     * parses to the same `(ManaColor, Int)` multiset as [payCost]. Used by
     * Warp/Sneak/Flashback's per-printing cost resolution.
     */
    fun findAlternativeCostAbilityGrpId(
        cardGrpId: Int,
        keywordBaseId: Int,
        payCost: List<Pair<ManaColor, Int>>,
    ): Int? {
        val data = findByGrpId(cardGrpId) ?: return null
        val payKey = payCost.toMap()
        for ((abilityGrpId, _) in data.abilityIds) {
            val info = findAbilityInfo(abilityGrpId) ?: continue
            if (info.baseId != keywordBaseId) continue
            if (info.manaCost.toMap() == payKey) return abilityGrpId
        }
        return null
    }

    /**
     * Keyword presence lookup. [keywordAbilityId] is one of the well-known
     * ability identifiers from [KeywordAbilityIds]. Returns the per-card
     * ability grpId that represents the keyword, or null when the card
     * doesn't carry it.
     *
     * Two shapes are checked, in order:
     * 1. **Direct match**: the well-known id appears verbatim in
     *    `card.abilityIds`. Used for cost-uniform keywords (Prowess, Haste,
     *    etc.) where every card with the keyword references the same shared
     *    ability id.
     * 2. **BaseId chain**: an ability on the card has `BaseId =
     *    keywordAbilityId`. Used for alt-cost keywords (Warp, Sneak,
     *    Flashback, Madness) where each printing has its own ability row
     *    with a per-printing mana cost, all chained to the keyword's
     *    well-known base id.
     */
    fun findKeywordAbilityGrpId(
        cardGrpId: Int,
        keywordAbilityId: Int,
    ): Int? {
        val data = findByGrpId(cardGrpId) ?: return null
        // Direct match
        for ((abilityGrpId, _) in data.abilityIds) {
            if (abilityGrpId == keywordAbilityId) return abilityGrpId
        }
        // BaseId chain
        for ((abilityGrpId, _) in data.abilityIds) {
            val info = findAbilityInfo(abilityGrpId) ?: continue
            if (info.baseId == keywordAbilityId) return abilityGrpId
        }
        return null
    }

    /** True iff [cardGrpId] carries any keyword ability in [keywordAbilityIds]. */
    fun hasAnyKeyword(
        cardGrpId: Int,
        keywordAbilityIds: Set<Int>,
    ): Boolean = keywordAbilityIds.any { findKeywordAbilityGrpId(cardGrpId, it) != null }

    /**
     * Token grpId produced by [sourceGrpId].
     * Single token -> returns directly. Multiple -> matches by [tokenName].
     */
    fun tokenGrpIdForCard(
        sourceGrpId: Int,
        tokenName: String? = null,
    ): Int? {
        val data = findByGrpId(sourceGrpId) ?: return null
        val tokens = data.tokenGrpIds
        if (tokens.isEmpty()) return null
        if (tokens.size == 1) return tokens.values.first()
        if (tokenName == null) return null
        // Forge names tokens "Rat Token", client DB uses "Rat" — try both
        val normalized = tokenName.removeSuffix(" Token")
        for ((_, tokenGrpId) in tokens) {
            val name = findNameByGrpId(tokenGrpId) ?: continue
            if (name == tokenName || name == normalized) return tokenGrpId
        }
        return null
    }
}

/**
 * Modal ability info: parent ability grpId and list of child option grpIds.
 * Used for CastingTimeOptionsReq (modal ETB, modal cast, etc.).
 */
data class ModalAbilityInfo(
    val parentGrpId: Int,
    val childGrpIds: List<Int>,
)

/**
 * Single row from the client's Abilities table. Minimal fields needed to
 * disambiguate keyword alt-cost rows (Warp, Sneak, …) within a card.
 */
data class AbilityInfo(
    val baseId: Int,
    val manaCost: List<Pair<ManaColor, Int>>,
)

/**
 * Well-known keyword ability identifiers from the client's `Abilities` table.
 *
 * Two shapes mix here, both consumed by [CardRepository.findKeywordAbilityGrpId]:
 *
 * - **Direct ability ids** (Prowess, Haste, …) — the integer is itself the
 *   ability id and appears verbatim in `Cards.AbilityIds` for every card
 *   carrying the keyword. Cost-uniform keywords land here.
 * - **BaseId chain** (Warp, Sneak, Flashback, Madness) — per-printing
 *   ability rows have varying mana costs but share a `BaseId` pointing at
 *   the keyword's definition row. The integer here is that shared base.
 *
 * Extend when a mapper/resolver layer needs to address another keyword.
 * Identify the integer by inspecting a sample card's fixture YAML or
 * querying the client's `Abilities` / `Localizations_enUS` tables.
 */
object KeywordAbilityIds {
    // Direct ability ids — well-known shared row used verbatim per card.
    const val HASTE = 9
    const val PROWESS = 137

    // BaseId roots — each printing has its own ability row chaining to this.
    const val FLASHBACK = 35
    const val MADNESS = 36
    const val WARP = 371
    const val SNEAK = 394

    /**
     * Resolve a Forge `AlternativeCost.name` (uppercase enum name like
     * `"WARP"`, `"SNEAK"`, `"FLASHBACK"`) to the keyword's ability id.
     * Returns null for alt-costs we don't have a client identifier for yet.
     */
    fun fromForgeAltCostName(name: String): Int? =
        when (name.uppercase()) {
            "WARP" -> WARP
            "SNEAK" -> SNEAK
            "FLASHBACK" -> FLASHBACK
            "MADNESS" -> MADNESS
            else -> null
        }
}
