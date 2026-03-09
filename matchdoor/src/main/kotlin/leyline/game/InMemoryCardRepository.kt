package leyline.game

/**
 * In-memory [CardRepository] for tests and puzzle mode.
 *
 * Provides [register], [registerData], and [clear] for populating
 * card data without a client SQLite database.
 */
class InMemoryCardRepository : CardRepository {

    private val cache = mutableMapOf<Int, CardData>()
    private val grpIdToName = mutableMapOf<Int, String>()
    private val nameToGrpId = mutableMapOf<String, Int>()
    private val modalCache = mutableMapOf<Int, ModalAbilityInfo>()

    val registeredCount: Int get() = grpIdToName.size

    fun register(grpId: Int, cardName: String) {
        grpIdToName[grpId] = cardName
        nameToGrpId[cardName] = grpId
    }

    fun registerData(data: CardData, cardName: String) {
        register(data.grpId, cardName)
        cache[data.grpId] = data
    }

    fun clear() {
        grpIdToName.clear()
        nameToGrpId.clear()
        cache.clear()
        modalCache.clear()
    }

    override fun findByGrpId(grpId: Int): CardData? = cache[grpId]

    override fun findNameByGrpId(grpId: Int): String? = grpIdToName[grpId]

    override fun findGrpIdByName(name: String): Int? = nameToGrpId[name]

    override fun findAllGrpIds(): List<Int> = grpIdToName.keys.toList()

    override fun lookupModalOptions(cardGrpId: Int): ModalAbilityInfo? = modalCache[cardGrpId]

    override fun registerModalOptions(cardGrpId: Int, info: ModalAbilityInfo) {
        modalCache[cardGrpId] = info
    }
}
