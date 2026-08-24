package leyline.domain.deck

import kotlinx.serialization.Serializable
import leyline.domain.DeckCard

/** Section a decklist line or resolved card belongs to. */
enum class DecklistSection {
    Main,
    Sideboard,
    Commander,
    Companion,
}

/** One unresolved decklist line: quantity, card name, and an optional set code hint. */
data class DecklistEntry(
    val section: DecklistSection,
    val quantity: Int,
    val name: String,
    val setCode: String? = null,
)

/** A parsed, unresolved decklist — sectioned entries in source order. */
data class Decklist(
    val entries: List<DecklistEntry>,
)

/** Canonical sectioned grpId value a resolved deck is built from. */
@Serializable
data class DeckCards(
    val mainDeck: List<DeckCard>,
    val sideboard: List<DeckCard> = emptyList(),
    val commandZone: List<DeckCard> = emptyList(),
    val companions: List<DeckCard> = emptyList(),
)

/** A decklist or card resolution failed. Carries every failure, not just the first. */
class DecklistException(
    val errors: List<String>,
) : Exception(errors.joinToString("; "))

private val LINE_REGEX = Regex("""^(\d+)\s+(.+)$""")
private val SET_PAREN_REGEX = Regex("""\s*\([A-Za-z0-9]+\)\s*\d*\s*$""")
private val PIPE_SET_REGEX = Regex("""\|[A-Za-z0-9]+$""")
private val SECTION_BRACKET_REGEX = Regex("""^\[(\w+)]$""")
private val SECTION_LABEL_REGEX = Regex("""^(Sideboard|Commander|Companion)\s*$""", RegexOption.IGNORE_CASE)
private val COMMENT_REGEX = Regex("""^[#;]|^//""")
private val SET_CODE_REGEX = Regex("""\(([A-Za-z0-9]+)\)""")

/**
 * Parse bundled and Web-import decklist text into sectioned, unresolved entries.
 * Grammar: `N Name`, `N Name (SET) NUM`, `N Name|SET`, `[Section]` and bare
 * `Sideboard`/`Commander`/`Companion` headers, `#`/`;`/`//` comments.
 * Rejects the whole input on a malformed quantity or an empty result.
 */
fun parseDecklist(text: String): Decklist {
    val entries = mutableListOf<DecklistEntry>()
    val errors = mutableListOf<String>()
    var section = DecklistSection.Main

    for (rawLine in text.lines()) {
        val line = rawLine.trim()
        if (line.isBlank() || COMMENT_REGEX.containsMatchIn(line)) continue

        val bracket = SECTION_BRACKET_REGEX.matchEntire(line)
        if (bracket != null) {
            section = sectionFor(bracket.groupValues[1]) ?: DecklistSection.Main
            continue
        }
        val label = SECTION_LABEL_REGEX.matchEntire(line)
        if (label != null) {
            section = sectionFor(label.groupValues[1]) ?: DecklistSection.Main
            continue
        }

        val match = LINE_REGEX.matchEntire(line)
        val quantity = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val rawName = match?.groupValues?.get(2) ?: line
        val cleaned = rawName.replace(SET_PAREN_REGEX, "").replace(PIPE_SET_REGEX, "").trim()
        if (cleaned.isBlank()) continue
        if (quantity <= 0) {
            errors += "Invalid quantity on line: $rawLine"
            continue
        }

        val setCode = SET_CODE_REGEX.find(rawName)?.groupValues?.get(1)
        entries += DecklistEntry(section, quantity, cleaned, setCode)
    }

    if (errors.isNotEmpty()) throw DecklistException(errors)
    if (entries.isEmpty()) throw DecklistException(listOf("No cards found in decklist"))
    return Decklist(entries)
}

private fun sectionFor(label: String): DecklistSection? =
    when (label.trim().lowercase()) {
        "sideboard" -> DecklistSection.Sideboard
        "commander" -> DecklistSection.Commander
        "companion" -> DecklistSection.Companion
        "deck", "main", "maybeboard" -> DecklistSection.Main
        else -> null
    }

/**
 * Resolve every entry's name (+ optional set code) to a grpId via [resolve].
 * All-or-nothing: any unresolved entry rejects the whole decklist.
 */
fun Decklist.resolveCards(resolve: (name: String, setCode: String?) -> Int?): DeckCards {
    val errors = mutableListOf<String>()
    val main = mutableListOf<DeckCard>()
    val sideboard = mutableListOf<DeckCard>()
    val commandZone = mutableListOf<DeckCard>()
    val companions = mutableListOf<DeckCard>()

    for (entry in entries) {
        val grpId = resolve(entry.name, entry.setCode)
        if (grpId == null) {
            errors += "Card not found: ${entry.name}"
            continue
        }
        val card = DeckCard(grpId, entry.quantity)
        when (entry.section) {
            DecklistSection.Main -> main += card
            DecklistSection.Sideboard -> sideboard += card
            DecklistSection.Commander -> commandZone += card
            DecklistSection.Companion -> companions += card
        }
    }

    if (errors.isNotEmpty()) throw DecklistException(errors)
    return DeckCards(main, sideboard, commandZone, companions)
}
