package leyline.domain.deck

import kotlinx.serialization.Serializable
import leyline.domain.Deck
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

/** The one mapping from a persisted [Deck] to its [DeckCards] — every repository lookup uses this. */
fun Deck.toDeckCards(): DeckCards = DeckCards(mainDeck, sideboard, commandZone, companions)

/**
 * Where a Forge deck gets realized from — see
 * [leyline.bridge.bootstrap.DeckLoader], the sole realization seam. Lives in domain
 * (not alongside DeckLoader) because config-tier types reference it and must not
 * depend on the engine/bridge layer.
 */
@Serializable
sealed interface DeckSource {
    /** Resolved grpId cards from a Leyline decklist — parsed and resolved upstream. */
    @Serializable
    data class Cards(
        val cards: DeckCards,
    ) : DeckSource

    /** Genuine Forge/runtime decklist text — puzzles, harness, simclient fixtures. */
    @Serializable
    data class ForgeText(
        val text: String,
    ) : DeckSource
}

/** A decklist or card resolution failed. Carries every failure, not just the first. */
class DecklistException(
    val errors: List<String>,
) : Exception(errors.joinToString("; "))

private val LINE_REGEX = Regex("""^(\d+)x?\s+(.+)$""", RegexOption.IGNORE_CASE)
private val SET_PAREN_REGEX = Regex("""\s*\([A-Za-z0-9]+\)(?:\s+[A-Za-z0-9]+)?\s*$""")
private val SECTION_LABEL_REGEX =
    Regex("""^(?:\[(Deck|Main|Sideboard|Commander|Companion)]|(Deck|Main|Sideboard|Commander|Companion))\s*$""", RegexOption.IGNORE_CASE)
private val COMMENT_REGEX = Regex("""^[#;]|^//""")
private val SET_CODE_REGEX = Regex("""\(([A-Za-z0-9]+)\)""")

/**
 * Parse bundled and Web-import decklist text into sectioned, unresolved entries.
 * Grammar: `N Name` or `Nx Name`, optional `(SET)` and collector-number suffixes,
 * bare or bracketed `Deck`/`Main`/`Sideboard`/`Commander`/`Companion` headers, and
 * `#`/`;`/`//` comments. No pipe set-code suffix or quantity-less card lines —
 * Forge/runtime text already goes through
 * [leyline.bridge.bootstrap.DeckLoader]'s own [forge.deck.DeckRecognizer] grammar.
 * Rejects the whole input on any unrecognized line, a malformed quantity, or an
 * empty result.
 */
fun parseDecklist(text: String): Decklist {
    val entries = mutableListOf<DecklistEntry>()
    val errors = mutableListOf<String>()
    var section = DecklistSection.Main

    for (rawLine in text.lines()) {
        val line = rawLine.trim()
        if (line.isBlank() || COMMENT_REGEX.containsMatchIn(line)) continue

        val label = SECTION_LABEL_REGEX.matchEntire(line)
        if (label != null) {
            section = sectionFor(label.groupValues.drop(1).first { it.isNotEmpty() })
            continue
        }

        val match = LINE_REGEX.matchEntire(line)
        if (match == null) {
            errors += "Unrecognized line: $rawLine"
            continue
        }
        val quantity = match.groupValues[1].toIntOrNull()
        if (quantity == null || quantity <= 0) {
            errors += "Invalid quantity on line: $rawLine"
            continue
        }
        val rawName = match.groupValues[2]
        val cleaned = rawName.replace(SET_PAREN_REGEX, "").trim()
        if (cleaned.isBlank()) {
            errors += "Missing card name on line: $rawLine"
            continue
        }

        val setCode = SET_CODE_REGEX.find(rawName)?.groupValues?.get(1)
        entries += DecklistEntry(section, quantity, cleaned, setCode)
    }

    if (errors.isNotEmpty()) throw DecklistException(errors)
    if (entries.isEmpty()) throw DecklistException(listOf("No cards found in decklist"))
    return Decklist(entries)
}

private fun sectionFor(label: String): DecklistSection =
    when (label.trim().lowercase()) {
        "sideboard" -> DecklistSection.Sideboard
        "commander" -> DecklistSection.Commander
        "companion" -> DecklistSection.Companion
        else -> DecklistSection.Main // "deck", "main"
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
