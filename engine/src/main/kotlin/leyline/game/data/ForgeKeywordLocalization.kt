package leyline.game.data

import forge.game.cost.Cost
import forge.game.keyword.Equip
import forge.game.keyword.Keyword
import forge.game.keyword.KeywordInterface
import forge.game.keyword.KeywordWithCost
import leyline.bridge.types.manaTokenToPair

/** Catalog definitions have no recipient; only live Forge keywords resolve recipient costs and amounts. */
internal fun localizeGrantedKeyword(keyword: String): AbilityLocalization? {
    val definition = keywordDefinition(keyword) ?: return null
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
    val hostCostKeyword = takesHostCost(definition)
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
 * The Forge keyword a declaration names, with a calculated amount `N` read as the symbolic `X`,
 * or null when Forge does not define the keyword.
 */
internal fun keywordDefinition(keyword: String): KeywordInterface? {
    val symbolicKeyword =
        keyword.split(':').mapIndexed { index, value -> if (index == 1 && value == "N") "X" else value }.joinToString(":")
    return Keyword.getInstance(symbolicKeyword).takeUnless { it.keyword == Keyword.UNDEFINED }
}

/**
 * Player-facing text for a keyword a card declares: the keyword title Forge renders, such as `Equip {3}`
 * or `Crew 1`. Equip adds its recipient label and flavor name where Forge's equip ability puts them
 * (`Equip Wizard you control {1}`, `Death Sickle — Equip {2}`). A cost taken from the host card has no
 * catalog value, so that keyword reads as its name.
 */
internal fun declaredKeywordText(
    declaration: String,
    definition: KeywordInterface,
): String {
    val name = definition.keyword.toString()
    if (definition is KeywordWithCost && takesHostCost(definition)) return name
    val title = runCatching { definition.title }.getOrNull() ?: return name
    if (definition !is Equip) return title
    val flavor = declaration.substringAfter(":Flavor ", "").takeIf { it.isNotEmpty() }
    val recipient =
        declaration
            .substringBefore(":Flavor ")
            .split(':')
            .getOrNull(3)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val labelled = recipient?.let { title.replaceFirst(name, "$name $it") } ?: title
    return flavor?.let { "$it — $labelled" } ?: labelled
}

private fun takesHostCost(definition: KeywordWithCost): Boolean = definition.costString in HOST_COST_KEYWORDS

/**
 * Costs a granted keyword takes from its host card instead of its own detail text.
 * `CardManaCost` is the continuous-effect placeholder Forge replaces with each
 * recipient's mana cost.
 */
private val HOST_COST_KEYWORDS = setOf("ManaCost", "CardManaCost", "")
