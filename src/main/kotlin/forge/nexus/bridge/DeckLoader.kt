package forge.nexus.bridge

import forge.deck.Deck
import forge.deck.DeckSection
import forge.item.PaperCard
import forge.model.FModel

object DeckLoader {

    /**
     * Parses a free-form text decklist into a Deck.
     * Accepts standard formats from Aetherhub/Moxfield/Arena/MTGO:
     *   4 Lightning Bolt
     *   2 Counterspell|M10
     *   4 Lightning Bolt (M10) 123
     * Section headers (Commander, Deck, Sideboard) switch the target DeckSection.
     * Comments (#, ;, //), blank lines, and unrecognized headers are skipped.
     */
    fun parseDeckList(text: String): Deck {
        val deck = Deck()
        var currentSection = DeckSection.Main
        var parsed = 0

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith(";") || line.startsWith("//")) continue

            val nextSection = detectSection(line)
            if (nextSection != null) {
                currentSection = nextSection
                continue
            }

            val (count, cardName, setCode) = parseFreeformLine(line) ?: continue
            val paperCard = findPaperCard(cardName, setCode)
            if (paperCard == null) {
                System.err.println("Warning: Card not found in database: $cardName (set: $setCode)")
                continue
            }
            deck.getOrCreate(currentSection).add(paperCard, count)
            parsed += count
        }

        if (parsed == 0) {
            throw IllegalArgumentException("No valid cards found in decklist")
        }
        return deck
    }

    // --- Helpers ---

    private val SECTION_HEADER = Regex(
        """^\[.+]$|^(Deck|Sideboard|Maybeboard|Commander|Companion)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun isSectionHeader(line: String): Boolean = SECTION_HEADER.matches(line)

    /** Return the DeckSection for a recognized header, or null if the line is not a header. */
    private fun detectSection(line: String): DeckSection? {
        if (!isSectionHeader(line)) return null
        val normalized = line.removeSurrounding("[", "]").trim().lowercase()
        return when {
            normalized == "commander" -> DeckSection.Commander
            normalized == "sideboard" -> DeckSection.Sideboard
            normalized == "companion" -> DeckSection.Sideboard
            // "deck", "main", "maybeboard", or bracket sections → Main
            else -> DeckSection.Main
        }
    }

    /** Parses "4 Lightning Bolt", "4 Lightning Bolt|M10", "4 Lightning Bolt (M10) 123" */
    private fun parseFreeformLine(line: String): Triple<Int, String, String?>? {
        val match = Regex("""^(\d+)\s+(.+)$""").matchEntire(line)
        val count: Int
        var rest: String
        if (match != null) {
            count = match.groupValues[1].toIntOrNull() ?: return null
            rest = match.groupValues[2]
        } else {
            count = 1
            rest = line
        }

        rest = rest.replace(Regex("""\s*\([A-Z0-9]+\)\s*\d*\s*$"""), "").trim()

        val pipeParts = rest.split("|")
        val cardName = pipeParts[0].trim()
        val setCode = if (pipeParts.size > 1) pipeParts[1].trim().ifEmpty { null } else null

        if (cardName.isEmpty()) return null
        return Triple(count, cardName, setCode)
    }

    private fun findPaperCard(cardName: String, setCode: String?): PaperCard? {
        val cardDb = FModel.getMagicDb().commonCards

        if (!setCode.isNullOrBlank()) {
            val card = cardDb.getCard(cardName, setCode)
            if (card != null) return card
        }

        return cardDb.getCard(cardName)
    }
}
