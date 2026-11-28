package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.keyword.Keyword
import forge.game.spellability.SpellAbility
import org.slf4j.LoggerFactory

/** Recognizes the one protocol-grounded multi-quality library-search shape; anything else stays a flat search. */
object GroupedSearchClassifier {
    private val log = LoggerFactory.getLogger(GroupedSearchClassifier::class.java)

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

        // The grounded request carries two non-empty, disjoint groups. A library that holds only one quality
        // stays a flat search. Overlap or a card outside both groups means the classifier disagrees with
        // Forge's search filter; it also stays flat so the player still gets a legal choice.
        val instant = candidates.indices.filter { candidates[it].isInstant }
        val flash = candidates.indices.filter { candidates[it].hasFlash }
        val overlap = instant.intersect(flash.toSet())
        val ungrouped = candidates.indices.count { it !in instant && it !in flash }
        if (overlap.isNotEmpty() || ungrouped > 0) {
            log.warn(
                "Grouped search falls back to a flat search: {} candidates in both groups, {} in neither",
                overlap.size,
                ungrouped,
            )
            return null
        }
        if (instant.isEmpty() || flash.isEmpty()) {
            log.debug("Grouped search falls back to a flat search: one group has no candidate")
            return null
        }
        return listOf(instant, flash)
    }

    private fun SpellAbility.param(name: String): String? = if (hasParam(name)) getParam(name) else null
}
