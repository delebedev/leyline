package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.keyword.Keyword
import forge.game.spellability.SpellAbility

/** Recognizes protocol-grounded multi-quality library searches. */
object GroupedSearchClassifier {
    fun classify(
        ability: SpellAbility?,
        candidates: List<Card>,
    ): List<List<Int>>? {
        if (ability?.api != ApiType.ChangeZone) return null
        if (ability.param("Origin") != "Library" || ability.param("Destination") != "Hand") return null
        if (ability.param("ChangeNum")?.let { it != "1" } == true) return null
        val qualities = ability.param("ChangeType")?.split(',')?.map(String::trim) ?: return null
        if (qualities != listOf("Instant", "Card.hasKeywordFlash")) return null

        val instant = candidates.indices.filter { candidates[it].isInstant }
        check(candidates.none { it.isInstant && it.hasKeyword(Keyword.FLASH) }) {
            "Grouped search candidate belongs to multiple groups"
        }
        val flash = candidates.indices.filter { candidates[it].hasKeyword(Keyword.FLASH) }
        check(instant.isNotEmpty() && flash.isNotEmpty() && (instant + flash).size == candidates.size) {
            "Grouped search candidates do not form the grounded partitions"
        }
        return listOf(instant, flash)
    }

    private fun SpellAbility.param(name: String): String? = if (hasParam(name)) getParam(name) else null
}
