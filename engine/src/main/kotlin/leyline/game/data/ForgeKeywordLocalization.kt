package leyline.game.data

import forge.game.cost.Cost
import forge.game.keyword.Keyword
import forge.game.keyword.KeywordWithCost
import leyline.bridge.types.manaTokenToPair

/** Catalog definitions have no recipient; only live Forge keywords resolve recipient costs and amounts. */
internal fun localizeGrantedKeyword(keyword: String): AbilityLocalization? {
    val symbolicKeyword =
        keyword.split(':').mapIndexed { index, value -> if (index == 1 && value == "N") "X" else value }.joinToString(":")
    val definition = Keyword.getInstance(symbolicKeyword)
    if (definition.keyword == Keyword.UNDEFINED) return null
    val mana =
        keyword
            .substringAfter(':', "")
            .substringBefore(':')
            .split(Regex("\\s+"))
            .mapNotNull(::manaTokenToPair)
    // A `ManaCost` detail, or a granted keyword that states no cost at all, is
    // paid with the host card's mana cost. Forge reads the live host card for
    // that cost, and a catalog definition has none, so the definition's reminder
    // template is formatted with "its mana cost" instead.
    if (definition !is KeywordWithCost) {
        return AbilityLocalization(definition.reminderText, mana, definition.title)
    }
    val hostCostKeyword = definition.costString in HOST_COST_KEYWORDS
    val symbolicCost = definition.costString == "ManaCost" || (hostCostKeyword && definition.keyword.reminderText.contains("%s"))
    if (!symbolicCost) {
        return AbilityLocalization(
            definition.reminderText,
            mana,
            if (hostCostKeyword) definition.keyword.toString() else definition.title,
        )
    }

    val name = definition.keyword.toString()
    val reduction =
        if (definition.keyword == Keyword.MIRACLE) {
            keyword.split(':').getOrNull(2)?.takeUnless { it.startsWith("Flavor ") }?.let {
                " reduced by ${Cost(it, false).toSimpleString()}"
            }
        } else {
            null
        }
    val costDescription = "Its ${name.lowercase()} cost is equal to its mana cost${reduction.orEmpty()}."
    val reminder = definition.keyword.reminderText.replace("%s", "its mana cost")
    return AbilityLocalization("$reminder $costDescription", mana, name)
}

/**
 * Costs a granted keyword takes from its host card instead of its own detail text.
 * `CardManaCost` is the continuous-effect placeholder Forge replaces with each
 * recipient's mana cost.
 */
private val HOST_COST_KEYWORDS = setOf("ManaCost", "CardManaCost", "")
