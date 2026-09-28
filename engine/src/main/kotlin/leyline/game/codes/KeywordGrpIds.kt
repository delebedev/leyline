package leyline.game.codes

object KeywordGrpIds {
    private val table =
        mapOf(
            "Deathtouch" to 5,
            "Double Strike" to 4,
            "First Strike" to 6,
            "Flying" to 8,
            "Haste" to 7,
            "Hexproof" to 2,
            "Protection" to 21,
            "Indestructible" to 11,
            "Lifelink" to 12,
            "Menace" to 142,
            "Reach" to 13,
            "Trample" to 14,
            "Vigilance" to 15,
        )

    private val parameterized =
        mapOf(
            "Hexproof from white" to 191,
            "Hexproof from blue" to 192,
            "Hexproof from black" to 193,
            "Hexproof from red" to 194,
            "Hexproof from green" to 195,
            "Protection from white" to 185,
            "Protection from blue" to 186,
            "Protection from black" to 187,
            "Protection from red" to 188,
            "Protection from green" to 189,
        )

    fun forKeyword(keyword: String): Int? = parameterized[keyword] ?: table[keyword]

    fun forKeyword(
        title: String,
        baseKeyword: String,
    ): Int? = parameterized[title] ?: table[baseKeyword]
}
