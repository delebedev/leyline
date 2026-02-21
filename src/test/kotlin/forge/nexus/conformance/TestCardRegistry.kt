package forge.nexus.conformance

import forge.game.card.Card
import forge.model.FModel
import forge.nexus.game.CardDb
import org.slf4j.LoggerFactory

/**
 * Registers test deck cards in [CardDb] using [CardDataDeriver] — no SQLite.
 *
 * Enables [CardDb.testMode] so `lookupByName`/`lookup` never touch SQLite.
 * All card metadata is derived from Forge's in-memory CardRules at test startup.
 *
 * Synthetic grpIds start at 80000 (allocated by [CardDataDeriver]).
 */
object TestCardRegistry {
    private val log = LoggerFactory.getLogger(TestCardRegistry::class.java)

    /** Default deck card names (GameBridge.DEFAULT_DECK). */
    private val DEFAULT_DECK_CARDS = listOf(
        "Forest", "Llanowar Elves", "Elvish Mystic", "Giant Growth",
        "Mountain", "Raging Goblin",
    )

    /**
     * Auto-register a card by name. If already in CardDb, returns existing grpId.
     * Otherwise derives CardData from Forge's in-memory CardRules and registers it.
     * Returns the grpId (synthetic, 0 on failure).
     */
    fun ensureCardRegistered(cardName: String): Int {
        CardDb.getGrpId(cardName)?.let { return it }

        val db = FModel.getMagicDb()?.commonCards ?: run {
            log.warn("Card DB not initialized, cannot auto-register '{}'", cardName)
            return 0
        }
        val paperCard = db.getCard(cardName) ?: run {
            forge.StaticData.instance().attemptToLoadCard(cardName)
            db.getCard(cardName)
        } ?: run {
            log.warn("Card '{}' not found in Forge DB", cardName)
            return 0
        }

        val tempCard = Card.fromPaperCard(paperCard, null)
        val cardData = CardDataDeriver.fromForgeCard(tempCard)
        CardDb.registerData(cardData, cardName)
        log.debug("Auto-registered '{}' with grpId={}", cardName, cardData.grpId)
        return cardData.grpId
    }

    /**
     * Bulk-register all card names from a deck list string.
     * Parses "N CardName" lines, registers each unique name.
     */
    fun ensureDeckRegistered(deckList: String) {
        val names = deckList.trim().lines()
            .filter { it.isNotBlank() }
            .map { it.trim().replaceFirst(Regex("^\\d+\\s+"), "") }
            .distinct()
        for (name in names) {
            ensureCardRegistered(name)
        }
    }

    /**
     * Register all default deck cards and enable [CardDb.testMode].
     * Idempotent — safe to call from multiple test setup methods.
     */
    fun ensureRegistered() {
        CardDb.testMode = true
        if (CardDb.registeredCount > 0) return
        for (name in DEFAULT_DECK_CARDS) {
            ensureCardRegistered(name)
        }
    }
}
