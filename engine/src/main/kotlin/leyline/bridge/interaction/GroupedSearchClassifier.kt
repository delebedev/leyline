package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.keyword.Keyword
import forge.game.spellability.SpellAbility

/** Recognizes protocol-grounded multi-quality library searches. */
object GroupedSearchClassifier {
    data class Shape(
        val origin: String?,
        val destination: String?,
        val changeNum: String?,
        val changeType: String?,
    )

    data class Candidate(
        val isInstant: Boolean,
        val hasFlash: Boolean,
    )

    fun classify(
        ability: SpellAbility?,
        candidates: List<Card>,
    ): List<List<Int>>? =
        classify(
            isChangeZone = ability?.api == ApiType.ChangeZone,
            shape =
                Shape(
                    ability?.param("Origin"),
                    ability?.param("Destination"),
                    ability?.param("ChangeNum"),
                    ability?.param("ChangeType"),
                ),
            candidates = candidates.map { Candidate(it.isInstant, it.hasKeyword(Keyword.FLASH)) },
        )

    internal fun classify(
        isChangeZone: Boolean,
        shape: Shape,
        candidates: List<Candidate>,
    ): List<List<Int>>? {
        if (!isChangeZone || shape.origin != "Library" || shape.destination != "Hand") return null
        if (shape.changeNum?.let { it != "1" } == true) return null
        val qualities = shape.changeType?.split(',')?.map(String::trim) ?: return null
        if (qualities != listOf("Instant", "Card.hasKeywordFlash")) return null

        val instant = candidates.indices.filter { candidates[it].isInstant }
        check(candidates.none { it.isInstant && it.hasFlash }) { "Grouped search candidate belongs to multiple groups" }
        val flash = candidates.indices.filter { candidates[it].hasFlash }
        check(instant.isNotEmpty() && flash.isNotEmpty() && (instant + flash).size == candidates.size) {
            "Grouped search candidates do not form the grounded partitions"
        }
        return listOf(instant, flash)
    }

    private fun SpellAbility.param(name: String): String? = if (hasParam(name)) getParam(name) else null
}
