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

    /** Like [findGrpIdByName] but includes secondary faces (adventure, DFC back). */
    fun findGrpIdByNameAnyFace(name: String): Int? = findGrpIdByName(name)
    fun findGrpIdByNameAndSet(name: String, setCode: String): Int? = findGrpIdByName(name)

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
    fun registerModalOptions(cardGrpId: Int, info: ModalAbilityInfo) {}

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
