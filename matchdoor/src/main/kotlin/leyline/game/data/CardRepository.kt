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
     * Resolve the per-card keyword alt-cost ability grpId for [cardGrpId].
     *
     * Matches a row in [CardData.abilityIds] whose `BaseId` is the keyword's
     * (e.g. 371 = Warp, 394 = Sneak) AND whose `OldSchoolManaText` parses to
     * the same `(ManaColor, Int)` multiset as [payCost].
     *
     * @param altCostKeyword `WARP` or `SNEAK` (uppercase AlternativeCost name).
     * @param payCost the SA's effective mana cost as `(color, count)` pairs.
     *   Compared as multisets so ordering doesn't matter.
     */
    fun findAlternativeCostAbilityGrpId(
        cardGrpId: Int,
        altCostKeyword: String,
        payCost: List<Pair<ManaColor, Int>>,
    ): Int? {
        val baseId = KEYWORD_BASE_IDS[altCostKeyword.uppercase()] ?: return null
        val data = findByGrpId(cardGrpId) ?: return null
        val payKey = payCost.toMap()
        var costUnknownMatch: Int? = null
        for ((abilityGrpId, _) in data.abilityIds) {
            val info = findAbilityInfo(abilityGrpId) ?: continue
            if (info.baseId != baseId) continue
            if (info.manaCost.toMap() == payKey) return abilityGrpId
            // Test-path tolerance: InMemoryCardRepository auto-seeds AbilityInfo
            // from the derived keyword map with an unknown (empty) manaCost. Fall
            // back to the first baseId match in that case so cost-unaware tests
            // still resolve. Prod ExposedCardRepository always sets manaCost.
            if (info.manaCost.isEmpty() && costUnknownMatch == null) costUnknownMatch = abilityGrpId
        }
        return costUnknownMatch
    }

    /**
     * Resolve the per-card ability grpId whose `BaseId` matches [keywordPrefix]
     * (uppercase keyword name, matched via [KEYWORD_BASE_IDS]). Cost-agnostic —
     * returns the first matching row. For cost-aware lookup use
     * [findAlternativeCostAbilityGrpId].
     *
     * Replaces the removed `CardData.keywordAbilityGrpIds` map. Callers that
     * previously used `startsWith(keyword)` over that map now go through the
     * Abilities-table `BaseId` chain — works in prod + tests uniformly when
     * `AbilityInfo` is registered.
     *
     * Returns null if the keyword isn't in [KEYWORD_BASE_IDS] or no matching row
     * exists. Unknown keywords are silently ignored (same behavior as the old
     * map lookup when the keyword wasn't populated).
     */
    fun findKeywordAbilityGrpId(
        cardGrpId: Int,
        keywordPrefix: String,
    ): Int? {
        val baseId = KEYWORD_BASE_IDS[keywordPrefix.uppercase()] ?: return null
        val data = findByGrpId(cardGrpId) ?: return null
        for ((abilityGrpId, _) in data.abilityIds) {
            val info = findAbilityInfo(abilityGrpId) ?: continue
            if (info.baseId == baseId) return abilityGrpId
        }
        return null
    }

    /**
     * True iff [cardGrpId] carries any keyword ability in [keywordPrefixes]
     * (uppercase keyword names). Helper for keyword-presence checks.
     */
    fun hasAnyKeyword(
        cardGrpId: Int,
        keywordPrefixes: Set<String>,
    ): Boolean = keywordPrefixes.any { findKeywordAbilityGrpId(cardGrpId, it) != null }

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
        // Forge names tokens "Rat Token", Arena DB uses "Rat" — try both
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
 * Keyword name (uppercase) → Arena DB `Abilities.BaseId` for that keyword.
 *
 * Covers keywords the mapper/resolver layers need to address by name. Observed
 * via corpus + card-lookup playbook:
 *  - Warp: BaseId=371 (alt-cost hand-cast rail)
 *  - Sneak: BaseId=394 (alt-cost hand-cast rail)
 *  - Flashback: BaseId=35 (graveyard-cast rail)
 *
 * Extend when adding another keyword we need to resolve by name (to a
 * per-card ability row). Keywords not in this map return null from
 * [CardRepository.findKeywordAbilityGrpId].
 *
 * TODO(leyline-9n6): populate BaseIds for Escape, Madness, Mayhem,
 *   Commander (and any other alt-cost / zone-cast keyword the mapper
 *   dispatches on) once verified against recordings. Until then those
 *   keywords resolve to null here — matching prior production behavior
 *   where `ExposedCardRepository` left the (now-deleted) map empty.
 */
val KEYWORD_BASE_IDS: Map<String, Int> =
    mapOf(
        "WARP" to 371,
        "SNEAK" to 394,
        "FLASHBACK" to 35,
        "PLOTTED" to 328,
        "FORETELL" to 208,
    )
