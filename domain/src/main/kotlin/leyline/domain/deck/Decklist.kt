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
private val FOIL_MARKER_REGEX = Regex("""\s*\*[FE]\*\s*$""", RegexOption.IGNORE_CASE)
private val PRINTING_SUFFIX_REGEX = Regex("""\s*\(([A-Za-z0-9]+)\)\s*([^\s()|]+)?\s*$""")
private val METADATA_ENTRY_REGEX = Regex("""^[^=]+=.*$""")
private val SECTION_LABEL_REGEX =
    Regex("""^(Deck|Main|Mainboard|Sideboard|Commander|Companion|Maybeboard)$""", RegexOption.IGNORE_CASE)
private val CATEGORY_LABEL_REGEX =
    Regex("""^(Artifacts?|Battles?|Creatures?|Enchantments?|Instants?|Lands?|Planeswalkers?|Sorcery|Sorceries)$""", RegexOption.IGNORE_CASE)
private val COMMENT_REGEX = Regex("""^[#;]|^//""")

/**
 * Parse bundled and Web-import decklist text into sectioned, unresolved entries.
 * Grammar: `N Name` or `Nx Name`, optional printing suffixes — `(SET)`, a
 * collector number (`146`, `18a`, `C17-222`, `1492★`) with or without space
 * after the set code, and foil/etched markers (`*F*`, `*E*`) — bare or
 * bracketed `Deck`/`Main`/`Mainboard`/`Sideboard`/`Commander`/`Companion`
 * headers with optional trailing colons, and `#`/`;`/`//` comments. Forge
 * `.dck` text is accepted too: a `[metadata]` block of `Key=Value` lines, and
 * `N Name|SET|art` card lines in the same sections. Card-type category headers
 * preserve the current section. `Maybeboard` entries are syntax-checked but
 * excluded. No quantity-less card lines. Rejects the whole input on any
 * unrecognized line, a malformed quantity, or an empty result.
 */
fun parseDecklist(text: String): Decklist {
    val entries = mutableListOf<DecklistEntry>()
    val errors = mutableListOf<String>()
    var section = DecklistSection.Main
    var excluded = false
    var inMetadata = false

    for (rawLine in text.lines()) {
        val line = rawLine.trim()
        if (line.isBlank() || COMMENT_REGEX.containsMatchIn(line)) continue

        val header =
            line
                .removeSuffix(":")
                .trim()
                .removeSurrounding("[", "]")
                .trim()
        if (header.equals("metadata", ignoreCase = true)) {
            inMetadata = true
            continue
        }
        val label = SECTION_LABEL_REGEX.matchEntire(header)
        if (label != null) {
            val name = label.groupValues[1]
            inMetadata = false
            excluded = name.equals("maybeboard", ignoreCase = true)
            if (!excluded) section = sectionFor(name)
            continue
        }
        if (inMetadata) {
            if (METADATA_ENTRY_REGEX.matches(line)) continue
            errors += "Unrecognized line: $rawLine"
            continue
        }
        if (CATEGORY_LABEL_REGEX.matches(header)) continue

        val entry = parseCardLine(line, rawLine, section, errors) ?: continue
        if (!excluded) entries += entry
    }

    if (errors.isNotEmpty()) throw DecklistException(errors)
    if (entries.isEmpty()) throw DecklistException(listOf("No cards found in decklist"))
    return Decklist(entries)
}

/** Parse one `N Name ...` card line, appending a format error when it does not fit the grammar. */
private fun parseCardLine(
    line: String,
    rawLine: String,
    section: DecklistSection,
    errors: MutableList<String>,
): DecklistEntry? {
    val match = LINE_REGEX.matchEntire(line)
    if (match == null) {
        errors += "Unrecognized line: $rawLine"
        return null
    }
    val quantity = match.groupValues[1].toIntOrNull()
    if (quantity == null || quantity <= 0) {
        errors += "Invalid quantity on line: $rawLine"
        return null
    }
    val (name, setCode) = splitPrinting(match.groupValues[2])
    if (name.isBlank()) {
        errors += "Missing card name on line: $rawLine"
        return null
    }
    return DecklistEntry(section, quantity, name, setCode)
}

/**
 * Split a card line's trailing printing details from its name. Forge `.dck`
 * lines carry `Name|SET|art`; every other exporter appends `(SET)` and an
 * optional collector number, then an optional foil marker (`*F*` or `*E*`).
 * Set codes are matched case-insensitively by the card database, so they are
 * normalized to upper case here.
 */
private fun splitPrinting(raw: String): Pair<String, String?> {
    if ('|' in raw) {
        val fields = raw.split('|')
        val setCode =
            fields
                .getOrNull(1)
                ?.trim()
                ?.uppercase()
                ?.takeIf { it.isNotEmpty() && it != "???" }
        return fields
            .first()
            .trim()
            .removeSuffix("+")
            .trim() to setCode
    }

    val withoutFoil = raw.replace(FOIL_MARKER_REGEX, "")
    val suffix = PRINTING_SUFFIX_REGEX.find(withoutFoil)
    val name = if (suffix == null) withoutFoil.trim() else withoutFoil.substring(0, suffix.range.first).trim()
    val setCode = suffix?.groupValues?.get(1)?.uppercase()
    return name to setCode
}

private fun sectionFor(label: String): DecklistSection =
    when (label.trim().lowercase()) {
        "sideboard" -> DecklistSection.Sideboard
        "commander" -> DecklistSection.Commander
        "companion" -> DecklistSection.Companion
        else -> DecklistSection.Main // "deck", "main", "mainboard"
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
