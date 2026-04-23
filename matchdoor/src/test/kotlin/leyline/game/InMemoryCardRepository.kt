package leyline.game

import leyline.game.data.AbilityInfo
import leyline.game.data.CardData
import leyline.game.data.CardRepository
import leyline.game.data.KEYWORD_BASE_IDS
import leyline.game.data.ModalAbilityInfo

/**
 * In-memory [leyline.game.data.CardRepository] for tests and puzzle mode.
 *
 * Provides [register], [registerData], and [clear] for populating
 * card data without a client SQLite database.
 */
class InMemoryCardRepository : CardRepository {
    // ConcurrentHashMap: tests that run concurrent Kotest specs call register()
    // via TestCardRegistry from multiple threads; plain mutableMapOf races.
    private val cache = java.util.concurrent.ConcurrentHashMap<Int, CardData>()
    private val grpIdToName = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val nameToGrpId = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val modalCache = java.util.concurrent.ConcurrentHashMap<Int, ModalAbilityInfo>()
    private val abilityInfoCache = java.util.concurrent.ConcurrentHashMap<Int, AbilityInfo>()

    // Per-card keyword-name → abilityGrpId, populated by the test-side derivers
    // (CardDataDeriver, PuzzleCardRegistrar) from Forge's keyword list. Lets
    // tests look up the synthetic grpId for a keyword ability without querying
    // the Arena DB's Abilities table. Production code never touches this —
    // ExposedCardRepository returns null from findTestKeywordAbilityGrpId.
    private val keywordMaps = java.util.concurrent.ConcurrentHashMap<Int, Map<String, Int>>()

    val registeredCount: Int get() = grpIdToName.size

    fun register(
        grpId: Int,
        cardName: String,
    ) {
        grpIdToName[grpId] = cardName
        nameToGrpId[cardName] = grpId
    }

    fun registerData(
        data: CardData,
        cardName: String,
    ) {
        require(data.grpId != 0) { "Cannot register card '$cardName' with grpId=0" }
        register(data.grpId, cardName)
        cache[data.grpId] = data
    }

    fun registerAbilityInfo(
        abilityGrpId: Int,
        info: AbilityInfo,
    ) {
        abilityInfoCache[abilityGrpId] = info
    }

    /** Register the per-card keyword-name → abilityGrpId map derived from Forge's
     *  keyword list. Used by test-side derivers; tests look up via
     *  [findTestKeywordAbilityGrpId] or [testKeywordAbilityGrpIds].
     *
     *  Also auto-seeds a minimal [AbilityInfo] (baseId from [KEYWORD_BASE_IDS],
     *  empty manaCost) for each keyword-name that maps to a known baseId — so
     *  the cost-agnostic lookups [CardRepository.findKeywordAbilityGrpId] and
     *  [CardRepository.findAbilityInfo] work in tests without every test site
     *  wiring its own [registerAbilityInfo]. Tests that need cost-matched
     *  lookups (e.g. [CardRepository.findAlternativeCostAbilityGrpId]) must
     *  still call [registerAbilityInfo] explicitly with the real manaCost. */
    fun registerKeywordAbilityGrpIds(
        cardGrpId: Int,
        map: Map<String, Int>,
    ) {
        if (map.isEmpty()) return
        keywordMaps[cardGrpId] = map
        for ((keyword, abilityGrpId) in map) {
            // Forge keyword strings have shapes like "WARP:1 G", "WARP {1}{G}", or "WARP" —
            // match against the keyword-name prefix. KEYWORD_BASE_IDS keys are bare
            // keyword names and none is a prefix of another, so startsWith is safe today.
            val upper = keyword.uppercase()
            val baseId = KEYWORD_BASE_IDS.entries.firstOrNull { upper.startsWith(it.key) }?.value ?: continue
            abilityInfoCache.putIfAbsent(abilityGrpId, AbilityInfo(baseId = baseId, manaCost = emptyList()))
        }
    }

    /** Test lookup: returns the first keyword-name entry starting with [prefix]
     *  (uppercase). Backstop for test scenarios where the Abilities-table
     *  BaseId isn't wired into [KEYWORD_BASE_IDS] yet. */
    fun findTestKeywordAbilityGrpId(
        cardGrpId: Int,
        prefix: String,
    ): Int? =
        keywordMaps[cardGrpId]
            ?.entries
            ?.firstOrNull { it.key.uppercase().startsWith(prefix.uppercase()) }
            ?.value

    /** Full per-card keyword map (uppercased keys). Empty if none registered. */
    fun testKeywordAbilityGrpIds(cardGrpId: Int): Map<String, Int> = keywordMaps[cardGrpId] ?: emptyMap()

    fun clear() {
        grpIdToName.clear()
        nameToGrpId.clear()
        cache.clear()
        modalCache.clear()
        abilityInfoCache.clear()
        keywordMaps.clear()
    }

    override fun findByGrpId(grpId: Int): CardData? = cache[grpId]

    override fun findNameByGrpId(grpId: Int): String? = grpIdToName[grpId]

    override fun findGrpIdByName(name: String): Int? = nameToGrpId[name]

    override fun findAllGrpIds(): List<Int> = grpIdToName.keys.toList()

    override fun lookupModalOptions(cardGrpId: Int): ModalAbilityInfo? = modalCache[cardGrpId]

    override fun registerModalOptions(
        cardGrpId: Int,
        info: ModalAbilityInfo,
    ) {
        modalCache[cardGrpId] = info
    }

    override fun findAbilityInfo(abilityGrpId: Int): AbilityInfo? = abilityInfoCache[abilityGrpId]

    /**
     * Test override: falls back to the test-side keyword map when the
     * Abilities-table path (via [KEYWORD_BASE_IDS]) doesn't cover the keyword.
     * Covers keywords we haven't populated BaseIds for yet (Madness, Mayhem,
     * Escape, Prowess, …) so tests exercise the same branching as prod without
     * manually wiring every keyword. Prod [ExposedCardRepository] uses the
     * default interface path only.
     */
    override fun findKeywordAbilityGrpId(
        cardGrpId: Int,
        keywordPrefix: String,
    ): Int? =
        super.findKeywordAbilityGrpId(cardGrpId, keywordPrefix)
            ?: findTestKeywordAbilityGrpId(cardGrpId, keywordPrefix)
}
