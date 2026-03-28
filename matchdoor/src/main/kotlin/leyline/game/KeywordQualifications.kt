package leyline.game

/**
 * Mapping table: keyword name → Arena Qualification annotation parameters.
 *
 * Populated from observing real server behavior. Each keyword needs a
 * game where a card with that keyword appears to capture the server's
 * grpId and qualificationType values.
 */
object KeywordQualifications {

    data class QualInfo(
        val grpId: Int,
        val qualificationType: Int,
        val qualificationSubtype: Int = 0,
    )

    private val table: Map<String, QualInfo> = mapOf(
        "Menace" to QualInfo(grpId = 142, qualificationType = 40),
        // Add entries as more keywords are observed in real server output:
        // "Flying" to QualInfo(grpId = ?, qualificationType = ?),
        // "Trample" to QualInfo(grpId = ?, qualificationType = ?),
        // "Lifelink" to QualInfo(grpId = ?, qualificationType = ?),
    )

    /** Look up Qualification parameters for a keyword. Returns null if unknown. */
    fun forKeyword(keyword: String): QualInfo? = table[keyword]

    /** All keywords with known Qualification mappings. */
    fun knownKeywords(): Set<String> = table.keys
}
