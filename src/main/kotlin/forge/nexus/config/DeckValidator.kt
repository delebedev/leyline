package forge.nexus.config

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Validates deck files before they reach the engine.
 *
 * Checks performed:
 * - File exists and is readable
 * - At least 1 card line parses successfully
 * - Total card count in [MIN_DECK_SIZE]..[MAX_DECK_SIZE]
 * - No non-basic card exceeds [MAX_COPIES] copies
 *
 * Validation happens at config load time (before engine boot),
 * so card DB is not available — only structural checks.
 */
object DeckValidator {
    private val log = LoggerFactory.getLogger(DeckValidator::class.java)

    const val MIN_DECK_SIZE = 40
    const val MAX_DECK_SIZE = 250
    const val MAX_COPIES = 4

    /** Well-known basic land names (any number of copies allowed). */
    private val BASIC_LANDS = setOf(
        "Plains", "Island", "Swamp", "Mountain", "Forest",
        "Snow-Covered Plains", "Snow-Covered Island", "Snow-Covered Swamp",
        "Snow-Covered Mountain", "Snow-Covered Forest",
        "Wastes",
    )

    /** Line pattern: `<count> <card name>` with optional set/collector suffix. */
    private val CARD_LINE = Regex("""^(\d+)\s+(.+)$""")

    /**
     * Validate a deck file. Returns a list of errors (empty = valid).
     */
    fun validate(file: File): List<String> {
        val errors = mutableListOf<String>()

        if (!file.exists()) {
            errors.add("Deck file not found: ${file.absolutePath}")
            return errors
        }
        if (!file.canRead()) {
            errors.add("Deck file not readable: ${file.absolutePath}")
            return errors
        }

        val lines = file.readLines()
        val cardCounts = mutableMapOf<String, Int>()
        var totalCards = 0
        var inSideboard = false

        for ((lineNum, rawLine) in lines.withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("//")) continue

            // Section headers
            val lower = line.lowercase()
            if (lower == "deck" || lower == "mainboard" || lower == "[main]") {
                inSideboard = false
                continue
            }
            if (lower == "sideboard" || lower == "[sideboard]" || lower == "companion" || lower == "[companion]") {
                inSideboard = true
                continue
            }
            // Skip sideboard cards for main deck validation
            if (inSideboard) continue

            val match = CARD_LINE.matchEntire(line)
            if (match == null) {
                // Could be an unrecognized section header — skip silently
                if (line.startsWith("[") || line.all { it.isLetter() || it.isWhitespace() }) continue
                errors.add("Line ${lineNum + 1}: cannot parse '$line'")
                continue
            }

            val count = match.groupValues[1].toIntOrNull()
            if (count == null || count <= 0) {
                errors.add("Line ${lineNum + 1}: invalid count in '$line'")
                continue
            }

            // Strip set code / collector number suffixes for name extraction
            val rawName = match.groupValues[2]
                .replace(Regex("""\s*\([A-Z0-9]+\)\s*\d*\s*$"""), "") // (SET) 123
                .split("|")[0] // name|SET
                .trim()

            totalCards += count
            cardCounts[rawName] = (cardCounts[rawName] ?: 0) + count
        }

        if (totalCards == 0) {
            errors.add("No valid card lines found")
            return errors
        }

        if (totalCards < MIN_DECK_SIZE) {
            errors.add("Deck has $totalCards cards, minimum is $MIN_DECK_SIZE")
        }
        if (totalCards > MAX_DECK_SIZE) {
            errors.add("Deck has $totalCards cards, maximum is $MAX_DECK_SIZE")
        }

        // Check 4-of limit (skip basic lands)
        for ((name, count) in cardCounts) {
            if (name in BASIC_LANDS) continue
            if (count > MAX_COPIES) {
                errors.add("'$name' has $count copies, maximum is $MAX_COPIES")
            }
        }

        if (errors.isEmpty()) {
            log.info("Deck validated: {} ({} cards, {} unique)", file.name, totalCards, cardCounts.size)
        } else {
            log.warn("Deck validation failed for {}: {}", file.name, errors)
        }

        return errors
    }

    /**
     * Validate and throw on failure. For use at startup.
     */
    fun validateOrThrow(file: File) {
        val errors = validate(file)
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(
                "Invalid deck '${file.name}':\n  ${errors.joinToString("\n  ")}",
            )
        }
    }
}
