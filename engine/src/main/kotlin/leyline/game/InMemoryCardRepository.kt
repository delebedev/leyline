package leyline.game

import leyline.game.data.AbilityInfo
import leyline.game.data.AbilityLocalization
import leyline.game.data.CardData
import leyline.game.data.CardRepository
import leyline.game.data.ModalAbilityInfo

/**
 * In-memory [leyline.game.data.CardRepository] for tests and puzzle mode.
 *
 * Provides [register], [registerData], [registerAbilityInfo],
 * [registerModalOptions], and [clear] for populating card data without
 * a client SQLite database. Keyword resolution uses the same
 * [findKeywordAbilityGrpId] entry point as production
 * (well-known ability ids from [leyline.game.data.KeywordAbilityIds]) —
 * no test-only fallback path.
 */
class InMemoryCardRepository : CardRepository {
    // ConcurrentHashMap: tests that run concurrent Kotest specs call register()
    // via TestCardRegistry from multiple threads; plain mutableMapOf races.
    private val cache = java.util.concurrent.ConcurrentHashMap<Int, CardData>()
    private val grpIdToName = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val nameToGrpId = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val modalCache = java.util.concurrent.ConcurrentHashMap<Int, ModalAbilityInfo>()
    private val abilityInfoCache = java.util.concurrent.ConcurrentHashMap<Int, AbilityInfo>()
    private val abilityLocalizationCache = java.util.concurrent.ConcurrentHashMap<Int, AbilityLocalization>()

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

    fun registerAbilityLocalization(
        abilityGrpId: Int,
        localization: AbilityLocalization,
    ) {
        abilityLocalizationCache[abilityGrpId] = localization
    }

    fun clear() {
        grpIdToName.clear()
        nameToGrpId.clear()
        cache.clear()
        modalCache.clear()
        abilityInfoCache.clear()
        abilityLocalizationCache.clear()
    }

    override fun findByGrpId(grpId: Int): CardData? = cache[grpId]

    override fun findNameByGrpId(grpId: Int): String? = grpIdToName[grpId]

    override fun findGrpIdByName(name: String): Int? = nameToGrpId[name]

    override fun findTokenGrpIdByName(name: String): Int? = nameToGrpId[name] ?: nameToGrpId[name.removeSuffix(" Token")]

    override fun findAllGrpIds(): List<Int> = grpIdToName.keys.toList()

    override fun lookupModalOptions(cardGrpId: Int): ModalAbilityInfo? = modalCache[cardGrpId]

    /** Register modal options on the concrete in-memory repository (fixture path only). */
    fun registerModalOptions(
        cardGrpId: Int,
        info: ModalAbilityInfo,
    ) {
        modalCache[cardGrpId] = info
    }

    override fun findAbilityInfo(abilityGrpId: Int): AbilityInfo? = abilityInfoCache[abilityGrpId]

    override fun findAbilityLocalization(abilityGrpId: Int): AbilityLocalization? = abilityLocalizationCache[abilityGrpId]
}
