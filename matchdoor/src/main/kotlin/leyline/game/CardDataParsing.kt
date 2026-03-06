package leyline.game

import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/** Parse "5" or "27,23" → list of ints. */
internal fun parseIntList(s: String?): List<Int> {
    if (s.isNullOrBlank()) return emptyList()
    return s.split(",").mapNotNull { it.trim().toIntOrNull() }
}

/** Parse "1005:227393" or "1005:227393 2010:300000" → list of (abilityGrpId, textId). */
internal fun parseAbilityIds(s: String?): List<Pair<Int, Int>> {
    if (s.isNullOrBlank()) return emptyList()
    return s.trim().split(" ", ",").mapNotNull { entry ->
        val parts = entry.split(":")
        if (parts.size == 2) {
            val base = parts[0].toIntOrNull() ?: return@mapNotNull null
            val id = parts[1].toIntOrNull() ?: return@mapNotNull null
            base to id
        } else {
            null
        }
    }
}

/**
 * Parse the client's OldSchoolManaText format into (ManaColor, count) pairs.
 * Format: "oG" = {G}, "o3oGoG" = {3}{G}{G}, "oXoRoR" = {X}{R}{R}.
 * Each "o" prefix starts a mana symbol; digits = generic count, letters = color.
 */
internal fun parseManaCost(s: String?): List<Pair<ManaColor, Int>> {
    if (s.isNullOrBlank()) return emptyList()
    val counts = mutableMapOf<ManaColor, Int>()
    for (part in s.split("o").filter { it.isNotEmpty() }) {
        when (part.uppercase()) {
            "W" -> counts.merge(ManaColor.White_afc9, 1, Int::plus)
            "U" -> counts.merge(ManaColor.Blue_afc9, 1, Int::plus)
            "B" -> counts.merge(ManaColor.Black_afc9, 1, Int::plus)
            "R" -> counts.merge(ManaColor.Red_afc9, 1, Int::plus)
            "G" -> counts.merge(ManaColor.Green_afc9, 1, Int::plus)
            "X" -> counts.merge(ManaColor.X, 1, Int::plus)
            "C" -> counts.merge(ManaColor.Colorless_afc9, 1, Int::plus)
            "S" -> counts.merge(ManaColor.Snow_afc9, 1, Int::plus)
            else -> {
                val n = part.toIntOrNull()
                if (n != null && n > 0) counts.merge(ManaColor.Generic, n, Int::plus)
            }
        }
    }
    return counts.toList()
}

/** Parse "99866:94161,175756:94156" → mapOf(99866 to 94161, 175756 to 94156). */
fun parseTokenGrpIds(s: String?): Map<Int, Int> {
    if (s.isNullOrBlank()) return emptyMap()
    val result = mutableMapOf<Int, Int>()
    for (entry in s.split(",")) {
        val parts = entry.trim().split(":")
        if (parts.size == 2) {
            val abilityGrpId = parts[0].toIntOrNull() ?: continue
            val tokenGrpId = parts[1].toIntOrNull() ?: continue
            result[abilityGrpId] = tokenGrpId
        }
    }
    return result
}
