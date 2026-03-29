package leyline.game

object KeywordGrpIds {
    private val table = mapOf(
        "Flying" to 8,
        "First Strike" to 6,
        "Trample" to 14,
        "Vigilance" to 15,
        "Lifelink" to 12,
        "Reach" to 13,
        "Menace" to 142,
    )
    fun forKeyword(keyword: String): Int? = table[keyword]
}
