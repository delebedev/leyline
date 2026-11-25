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
private val COMMANDER_MARKER_REGEX = Regex("""\s*\*CMDR\*\s*$""", RegexOption.IGNORE_CASE)
private val PRINTING_SUFFIX_REGEX = Regex("""\s*\(([A-Za-z0-9]+)\)\s*([^\s()|]+)?\s*$""")
private val TRAILING_TAG_REGEX = Regex("""\s*\[([^\[\]]*)\]\s*$""")
private val LEADING_TAG_REGEX = Regex("""^\[([^\[\]]*)\]\s+(\S.*)$""")
private val TRAILING_LABEL_REGEX = Regex("""\s*\^[^^]*\^\s*$""")
private val TAG_MODIFIER_REGEX = Regex("""\{[^}]*\}""")
private val HEADER_COUNT_REGEX = Regex("""\s*\(\d+\)$""")
private val METADATA_ENTRY_REGEX = Regex("""^[^=]+=.*$""")
private val DECK_NAME_LINE_REGEX = Regex("""^Name\s+\S.*$""")
private val SIDEBOARD_PREFIX_REGEX = Regex("""^SB:\s*""", RegexOption.IGNORE_CASE)
private val SIGNED_NUMBER_REGEX = Regex("""^[-+]?\d""")
private val CSV_ROW_REGEX = Regex("""^"?\d+"?\s*,""")
private val SECTION_LABEL_REGEX =
    Regex(
        """^(Deck|Main|Mainboard|Side|Sideboard|Commanders?|Companions?|Maybeboard)$""",
        RegexOption.IGNORE_CASE,
    )
private val CATEGORY_LABEL_REGEX =
    Regex("""^(Artifacts?|Battles?|Creatures?|Enchantments?|Instants?|Lands?|Planeswalkers?|Sorcery|Sorceries)$""", RegexOption.IGNORE_CASE)
private val COMMENT_REGEX = Regex("""^[#;]|^//""")
private const val NAME_QUOTES = "\"“”„"

/** Reads decklist lines in order, tracking the section, metadata block and CSV layout they set. */
private class DecklistReader {
    val entries = mutableListOf<DecklistEntry>()
    val errors = mutableListOf<String>()
    private var section = DecklistSection.Main
    private var excluded = false
    private var entriesInSection = 0
    private var inMetadata = false
    private var csvColumns: CsvColumns? = null

    fun read(rawLine: String) {
        val line = rawLine.trim()
        if (line.isBlank()) {
            leaveCommandBlock()
            return
        }
        if (COMMENT_REGEX.containsMatchIn(line) || readHeader(line)) return
        if (inMetadata) {
            if (!METADATA_ENTRY_REGEX.matches(line)) errors += "Unrecognized line: $rawLine"
            return
        }
        csvHeader(line)?.let {
            csvColumns = it
            return
        }
        val parsed =
            if (csvColumns != null || CSV_ROW_REGEX.containsMatchIn(line)) {
                parseCsvRow(line, rawLine, csvColumns ?: CsvColumns(0, 1), errors)
            } else {
                parseCardLine(line, rawLine, errors)
            } ?: return
        add(parsed)
    }

    /** Consume a section, category or metadata header line; false when the line is not one. */
    private fun readHeader(line: String): Boolean {
        val header =
            line
                .removeSuffix(":")
                .trim()
                .removeSurrounding("[", "]")
                .trim()
                .replace(HEADER_COUNT_REGEX, "")
        if (header.equals("metadata", ignoreCase = true) || header.equals("about", ignoreCase = true)) {
            inMetadata = true
            return true
        }
        val label = SECTION_LABEL_REGEX.matchEntire(header)?.groupValues?.get(1)
        if (label != null) {
            inMetadata = false
            enter(sectionFor(label), exclude = label.equals("maybeboard", ignoreCase = true))
            return true
        }
        if (DECK_NAME_LINE_REGEX.matches(line)) return true
        if (!inMetadata && CATEGORY_LABEL_REGEX.matches(header)) {
            leaveCommandBlock()
            return true
        }
        return false
    }

    private fun add(parsed: ParsedLine) {
        val (entry, tagged) = parsed.withTags()
        // A card's own section tag overrides the block it sits in.
        if (tagged.excluded || tagged.section == null && excluded) return
        entries += entry.copy(section = tagged.section ?: section)
        entriesInSection++
    }

    private fun enter(
        next: DecklistSection,
        exclude: Boolean = false,
    ) {
        excluded = exclude
        if (!exclude) section = next
        entriesInSection = 0
    }

    /** A commander or companion block holds only its own cards; what follows it is the main deck. */
    private fun leaveCommandBlock() {
        if (!excluded && section.isCommandBlock() && entriesInSection > 0) enter(DecklistSection.Main)
    }
}

private fun DecklistSection.isCommandBlock() = this == DecklistSection.Commander || this == DecklistSection.Companion

/**
 * Parse bundled and Web-import decklist text into sectioned, unresolved entries.
 *
 * Card lines are `N Name` or `Nx Name`. A line with no quantity is one copy, so
 * a name typed alone still reaches card resolution and fails there by name when
 * it is not a card. Accepted decorations:
 * - printing suffixes: `(SET)`, a collector number (`146`, `18a`, `C17-222`,
 *   `1492★`, `1p`) with or without space after the set code, and foil/etched
 *   markers (`*F*`, `*E*`);
 * - category tags written before or after the card, `[Land]`, `[Ramp,Removal]`,
 *   `[Commander{top}]`, and trailing `^label^` groups. A `Commander`,
 *   `Companion`, `Sideboard` or `Maybeboard` tag moves that card to that
 *   section; other tags are dropped. `*CMDR*` marks a commander;
 * - straight or curly double quotes around the name;
 * - an `SB:` prefix for a sideboard card;
 * - CSV rows `N,Name` or `N,"Name, With Comma"`, and a header row naming the
 *   `Count`/`Quantity` and `Name` columns when the columns are in another order.
 *
 * Section headers are bare or bracketed `Deck`, `Main`, `Mainboard`, `Side`,
 * `Sideboard`, `Commander(s)`, `Companion(s)` or `Maybeboard`, with an optional
 * trailing colon or `(count)`. A card-type category header keeps the current
 * section, except that it, or a blank line, ends a commander or companion block:
 * exports that group the main deck by type list it right after the commander.
 * `Maybeboard` entries are syntax-checked but excluded.
 *
 * Metadata is skipped: a Forge `.dck` `[metadata]` block of `Key=Value` lines,
 * an `About` block, and `Name <deck name>` lines. Forge `.dck` card lines
 * `N Name|SET|art` are accepted in the same sections. `#`, `;` and `//` start
 * comments.
 *
 * Rejects the whole input on any unrecognized line, a malformed quantity, or an
 * empty result.
 */
fun parseDecklist(text: String): Decklist {
    val reader = DecklistReader()
    text.lines().forEach(reader::read)
    if (reader.errors.isNotEmpty()) throw DecklistException(reader.errors)
    if (reader.entries.isEmpty()) throw DecklistException(listOf("No cards found in decklist"))
    return Decklist(reader.entries)
}

/** A card line before its section is applied: quantity, decorated name text, and any section tags. */
private data class ParsedLine(
    val quantity: Int,
    val text: String,
    val sideboardPrefix: Boolean = false,
)

private data class SectionTag(
    val section: DecklistSection? = null,
    val excluded: Boolean = false,
)

/** Strip tags, markers and printing details from the name text; report the section a tag selects. */
private fun ParsedLine.withTags(): Pair<DecklistEntry, SectionTag> {
    var raw = text
    val tags = mutableListOf<String>()
    while (true) {
        val label = TRAILING_LABEL_REGEX.find(raw)
        val tag = TRAILING_TAG_REGEX.find(raw)
        val cut = label ?: tag ?: break
        if (cut === tag) tags += tag.groupValues[1]
        raw = raw.substring(0, cut.range.first)
    }
    if (COMMANDER_MARKER_REGEX.containsMatchIn(raw)) {
        tags += "Commander"
        raw = raw.replace(COMMANDER_MARKER_REGEX, "")
    }
    val (name, setCode) = splitPrinting(raw)
    val sectionTag = tags.flatMap { it.split(',') }.map { it.replace(TAG_MODIFIER_REGEX, "").trim() }.sectionTag()
    val tag = if (sideboardPrefix && sectionTag == SectionTag()) SectionTag(DecklistSection.Sideboard) else sectionTag
    return DecklistEntry(DecklistSection.Main, quantity, name, setCode) to tag
}

private fun List<String>.sectionTag(): SectionTag {
    for (tag in this) {
        val label = SECTION_LABEL_REGEX.matchEntire(tag)?.groupValues?.get(1) ?: continue
        if (label.equals("maybeboard", ignoreCase = true)) return SectionTag(excluded = true)
        val section = sectionFor(label)
        if (section != DecklistSection.Main) return SectionTag(section)
    }
    return SectionTag()
}

/**
 * Parse one card line, appending a format error when it does not fit the grammar.
 * A line without a quantity is one copy unless it looks like a header.
 */
private fun parseCardLine(
    line: String,
    rawLine: String,
    errors: MutableList<String>,
): ParsedLine? {
    val sideboard = SIDEBOARD_PREFIX_REGEX.containsMatchIn(line)
    var body = line.replace(SIDEBOARD_PREFIX_REGEX, "")
    LEADING_TAG_REGEX.matchEntire(body)?.let { tagged ->
        // `[Commander] 1 Name`: the quantity comes first, so carry the tag after the name.
        body = "${tagged.groupValues[2]} [${tagged.groupValues[1]}]"
    }
    val match = LINE_REGEX.matchEntire(body)
    if (match == null && SIGNED_NUMBER_REGEX.containsMatchIn(body)) {
        errors += "Invalid quantity on line: $rawLine"
        return null
    }
    if (match == null) {
        if (body.endsWith(":") || body.startsWith("[") || body.none(Char::isLetter)) {
            errors += "Unrecognized line: $rawLine"
            return null
        }
        return ParsedLine(1, body, sideboard).checkedName(rawLine, errors)
    }
    val quantity = match.groupValues[1].toIntOrNull()
    if (quantity == null || quantity <= 0) {
        errors += "Invalid quantity on line: $rawLine"
        return null
    }
    return ParsedLine(quantity, match.groupValues[2], sideboard).checkedName(rawLine, errors)
}

private fun ParsedLine.checkedName(
    rawLine: String,
    errors: MutableList<String>,
): ParsedLine? {
    if (withTags().first.name.isBlank()) {
        errors += "Missing card name on line: $rawLine"
        return null
    }
    return this
}

/** Zero-based quantity and name columns of a CSV export. */
private data class CsvColumns(
    val quantity: Int,
    val name: Int,
)

/** The column layout a CSV header row names, or null when the line is not one. */
private fun csvHeader(line: String): CsvColumns? {
    if (',' !in line) return null
    val fields = splitCsv(line).map { it.trim().lowercase() }
    val quantity = fields.indexOfFirst { it in setOf("count", "quantity", "qty", "amount") }
    val name = fields.indexOfFirst { it == "name" || it == "card name" || it == "card" }
    return if (quantity >= 0 && name >= 0) CsvColumns(quantity, name) else null
}

private fun parseCsvRow(
    line: String,
    rawLine: String,
    columns: CsvColumns,
    errors: MutableList<String>,
): ParsedLine? {
    val fields = splitCsv(line)
    val quantity = fields.getOrNull(columns.quantity)?.trim()?.toIntOrNull()
    val name = fields.getOrNull(columns.name)?.trim()
    if (quantity == null || name.isNullOrEmpty()) {
        errors += "Unrecognized line: $rawLine"
        return null
    }
    if (quantity <= 0) {
        errors += "Invalid quantity on line: $rawLine"
        return null
    }
    return ParsedLine(quantity, name)
}

/** Split one CSV row on commas outside double quotes; `""` inside quotes is a literal quote. */
private fun splitCsv(line: String): List<String> {
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && quoted && line.getOrNull(i + 1) == '"' -> {
                field.append('"')
                i++
            }
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> {
                fields += field.toString()
                field.clear()
            }
            else -> field.append(c)
        }
        i++
    }
    fields += field.toString()
    return fields
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
            .trim()
            .unquoted() to setCode
    }

    val withoutFoil = raw.replace(FOIL_MARKER_REGEX, "")
    val suffix = PRINTING_SUFFIX_REGEX.find(withoutFoil)
    val name = if (suffix == null) withoutFoil.trim() else withoutFoil.substring(0, suffix.range.first).trim()
    val setCode = suffix?.groupValues?.get(1)?.uppercase()
    return name.unquoted() to setCode
}

/** Drop straight or curly double quotes an export or a paste left around a name, balanced or not. */
private fun String.unquoted(): String = trim { it in NAME_QUOTES || it.isWhitespace() }

private fun sectionFor(label: String): DecklistSection =
    when (label.trim().lowercase()) {
        "side", "sideboard" -> DecklistSection.Sideboard
        "commander", "commanders" -> DecklistSection.Commander
        "companion", "companions" -> DecklistSection.Companion
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
