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
    if (definition !is KeywordWithCost || definition.costString != "ManaCost") {
        return AbilityLocalization(definition.reminderText, mana, definition.title)
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
