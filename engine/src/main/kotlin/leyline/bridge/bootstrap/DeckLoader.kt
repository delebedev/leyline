package leyline.bridge.bootstrap

import forge.deck.Deck
import forge.deck.DeckRecognizer
import forge.item.PaperCard
import forge.model.FModel
import leyline.domain.deck.DeckCards
import leyline.domain.deck.DeckSource
import forge.deck.DeckSection as ForgeDeckSection

/** Deck realization failed. Carries every failure, not just the first. */
class DeckRealizationException(
    val errors: List<String>,
) : Exception(errors.joinToString("; "))

/** The only seam that turns a [DeckSource] into a Forge [Deck]. */
object DeckLoader {
    /**
     * Realize a Forge [Deck] from [source].
     *
     * [nameByGrpId] resolves grpId -> card name for [DeckSource.Cards]; unused for
     * [DeckSource.ForgeText], which already carries card names.
     */
    fun load(
        source: DeckSource,
        nameByGrpId: (Int) -> String? = { null },
    ): Deck =
        when (source) {
            is DeckSource.Cards -> buildFromCards(source.cards, nameByGrpId)
            is DeckSource.ForgeText -> buildFromForgeText(source.text)
        }

    private fun buildFromCards(
        cards: DeckCards,
        nameByGrpId: (Int) -> String?,
    ): Deck {
        val deck = Deck()
        val errors = mutableListOf<String>()

        fun place(
            section: ForgeDeckSection,
            entries: List<leyline.domain.DeckCard>,
        ) {
            for (entry in entries) {
                val name = nameByGrpId(entry.grpId)
                val card = name?.let { findPaperCard(it, null) }
                if (card == null) {
                    errors += "Unknown grpId ${entry.grpId}" + (name?.let { ": $it" } ?: "")
                    continue
                }
                deck.getOrCreate(section).add(card, entry.quantity)
            }
        }

        place(ForgeDeckSection.Main, cards.mainDeck)
        place(ForgeDeckSection.Sideboard, cards.sideboard)
        place(ForgeDeckSection.Commander, cards.commandZone)
        // Forge's match setup assigns companions from the sideboard zone.
        place(ForgeDeckSection.Sideboard, cards.companions)

        if (errors.isNotEmpty()) throw DeckRealizationException(errors)
        require(deck.getOrCreate(ForgeDeckSection.Main).countAll() > 0) { "No valid cards in deck" }
        return deck
    }

    /**
     * Realize Forge/runtime decklist text via Forge's own [DeckRecognizer] instead of
     * a Leyline regex parser. No format/set/ban constraints are configured, so every
     * card resolvable in the card database recognizes as legal.
     */
    private fun buildFromForgeText(text: String): Deck {
        val deck = Deck()
        val recognizer = DeckRecognizer()
        val errors = mutableListOf<String>()
        var section = ForgeDeckSection.Main
        var parsed = 0

        for (rawLine in text.lines()) {
            val token = recognizer.recognizeLine(rawLine, section) ?: continue
            when (token.type) {
                DeckRecognizer.TokenType.DECK_SECTION_NAME -> section = forgeSectionFor(token.text) ?: section
                DeckRecognizer.TokenType.COMMENT -> {}
                DeckRecognizer.TokenType.UNKNOWN_TEXT,
                DeckRecognizer.TokenType.UNKNOWN_CARD,
                DeckRecognizer.TokenType.UNSUPPORTED_CARD,
                DeckRecognizer.TokenType.CARD_FROM_NOT_ALLOWED_SET,
                DeckRecognizer.TokenType.CARD_FROM_INVALID_SET,
                DeckRecognizer.TokenType.WARNING_MESSAGE,
                -> errors += token.text
                else ->
                    if (token.isCardTokenForDeck) {
                        deck.getOrCreate(token.tokenSection ?: section).add(token.card, token.quantity)
                        parsed += token.quantity
                    }
            }
        }

        if (errors.isNotEmpty()) throw DeckRealizationException(errors)
        require(parsed > 0) { "No valid cards found in decklist" }
        return deck
    }

    private fun forgeSectionFor(name: String): ForgeDeckSection? =
        ForgeDeckSection.values().firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun findPaperCard(
        cardName: String,
        setCode: String?,
    ): PaperCard? {
        val cardDb = FModel.getMagicDb().commonCards

        if (!setCode.isNullOrBlank()) {
            val card = cardDb.getCard(cardName, setCode)
            if (card != null) return card
        }

        return cardDb.getCard(cardName)
    }
}
