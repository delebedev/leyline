package leyline.game.codes

import leyline.bridge.types.GrpId

/**
 * Mapping table: keyword name → client Qualification annotation parameters.
 *
 * Populated from protocol analysis. Each keyword needs a
 * reference scenario where a card with that keyword appears so the
 * grpId and qualificationType values can be recorded.
 */
object KeywordQualifications {
    data class QualInfo(
        val grpId: GrpId,
        val qualificationType: QualificationType,
        val qualificationSubtype: Int = 0,
    )

    private val table: Map<String, QualInfo> =
        mapOf(
            "Menace" to QualInfo(grpId = GrpId(142), qualificationType = QualificationType.CombatKeyword),
            // Add entries as more keywords are observed in protocol output:
            // "Flying" to QualInfo(grpId = ?, qualificationType = ?),
            // "Trample" to QualInfo(grpId = ?, qualificationType = ?),
            // "Lifelink" to QualInfo(grpId = ?, qualificationType = ?),
        )

    /** Look up Qualification parameters for a keyword. Returns null if unknown. */
    fun forKeyword(keyword: String): QualInfo? = table[keyword]

    /** All keywords with known Qualification mappings. */
    fun knownKeywords(): Set<String> = table.keys
}
