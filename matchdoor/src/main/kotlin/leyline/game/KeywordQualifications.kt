package leyline.game

/**
 * Mapping table: keyword name → Arena Qualification annotation parameters.
 *
 * Populated from real server recordings. Each keyword needs one recording
 * of a card with that keyword to capture the server's grpId and
 * qualificationType values. Unknown keywords log a warning at runtime
 * but don't crash.
 */
object KeywordQualifications {

    data class QualInfo(
        val grpId: Int,
        val qualificationType: Int,
        val qualificationSubtype: Int = 0,
    )

    private val table: Map<String, QualInfo> = mapOf(
        "Menace" to QualInfo(grpId = 142, qualificationType = 40),
        // Add entries as recordings provide data for other keywords:
        // "Flying" to QualInfo(grpId = ?, qualificationType = ?),
        // "Trample" to QualInfo(grpId = ?, qualificationType = ?),
        // "Lifelink" to QualInfo(grpId = ?, qualificationType = ?),
    )

    /** Look up Qualification parameters for a keyword. Returns null if unknown. */
    fun forKeyword(keyword: String): QualInfo? = table[keyword]

    /** All keywords with known Qualification mappings. */
    fun knownKeywords(): Set<String> = table.keys
}
