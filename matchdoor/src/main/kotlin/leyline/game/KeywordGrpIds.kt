package leyline.game

object KeywordGrpIds {
    private val table = mapOf(
        "Deathtouch" to 5,
        "Double Strike" to 4,
        "First Strike" to 6,
        "Flying" to 8,
        "Haste" to 7,
        "Hexproof" to 2,
        "Indestructible" to 11,
        "Lifelink" to 12,
        "Menace" to 142,
        "Reach" to 13,
        "Trample" to 14,
        "Vigilance" to 15,
    )
    fun forKeyword(keyword: String): Int? = table[keyword]
}
