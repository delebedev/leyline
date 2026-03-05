package leyline.game

/**
 * Read-only card data repository — abstracts the client's local card database.
 *
 * Production impl ([ExposedCardRepository]) reads from the client's SQLite.
 * Tests use [InMemoryCardRepository] with synthetic data derived from Forge.
 */
interface CardRepository {
    fun findByGrpId(grpId: Int): CardData?
    fun findNameByGrpId(grpId: Int): String?
    fun findGrpIdByName(name: String): Int?

    /**
     * Token grpId produced by [sourceGrpId].
     * Single token -> returns directly. Multiple -> matches by [tokenName].
     */
    fun tokenGrpIdForCard(sourceGrpId: Int, tokenName: String? = null): Int? {
        val data = findByGrpId(sourceGrpId) ?: return null
        val tokens = data.tokenGrpIds
        if (tokens.isEmpty()) return null
        if (tokens.size == 1) return tokens.values.first()
        if (tokenName == null) return null
        for ((_, tokenGrpId) in tokens) {
            val name = findNameByGrpId(tokenGrpId)
            if (name == tokenName) return tokenGrpId
        }
        return null
    }
}
