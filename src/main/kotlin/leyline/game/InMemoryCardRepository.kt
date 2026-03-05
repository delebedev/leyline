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
    }

    override fun findByGrpId(grpId: Int): CardData? = cache[grpId]

    override fun findNameByGrpId(grpId: Int): String? = grpIdToName[grpId]

    override fun findGrpIdByName(name: String): Int? = nameToGrpId[name]

    override fun tokenGrpIdForCard(sourceGrpId: Int, tokenName: String?): Int? {
        val data = findByGrpId(sourceGrpId) ?: return null
        val tokens = data.tokenGrpIds
        if (tokens.isEmpty()) return null
        if (tokens.size == 1) return tokens.values.first()
        if (tokenName == null) return null
        for ((_, tokenGrpId) in tokens) {
            val name = findNameByGrpId(tokenGrpId) ?: run {
                findByGrpId(tokenGrpId)
                findNameByGrpId(tokenGrpId)
            }
            if (name == tokenName) return tokenGrpId
        }
        return null
    }
}
