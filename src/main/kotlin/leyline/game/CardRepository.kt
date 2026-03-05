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
    fun tokenGrpIdForCard(sourceGrpId: Int, tokenName: String? = null): Int?
}
