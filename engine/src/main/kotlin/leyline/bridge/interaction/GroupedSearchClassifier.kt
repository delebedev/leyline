package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.keyword.Keyword
import forge.game.spellability.SpellAbility

/** Recognizes the one protocol-grounded multi-quality library-search shape; anything else stays a flat search. */
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
        if (shape.changeNum != "1") return null
        if (shape.changeType?.split(',')?.map(String::trim) != listOf("Instant", "Card.hasKeywordFlash")) return null

        // The grounded request carries two non-empty, disjoint groups. A library that holds only one quality,
        // or a card that qualifies for both, stays a flat search so the player still gets a legal choice.
        if (candidates.any { it.isInstant && it.hasFlash }) return null
        val instant = candidates.indices.filter { candidates[it].isInstant }
        val flash = candidates.indices.filter { candidates[it].hasFlash }
        if (instant.isEmpty() || flash.isEmpty() || instant.size + flash.size != candidates.size) return null
        return listOf(instant, flash)
    }

    private fun SpellAbility.param(name: String): String? = if (hasParam(name)) getParam(name) else null
}
