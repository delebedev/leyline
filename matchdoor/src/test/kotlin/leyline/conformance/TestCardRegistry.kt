package leyline.conformance

import forge.game.card.Card
import forge.model.FModel
import leyline.game.InMemoryCardRepository
import org.slf4j.LoggerFactory

/**
 * Registers test deck cards in the shared [InMemoryCardRepository] using [CardDataDeriver].
 *
 * All card metadata is derived from Forge's in-memory CardRules at test startup.
 * No SQLite needed.
 *
 * Synthetic grpIds start at 200000 (allocated by [CardDataDeriver]).
 */
object TestCardRegistry {
    private val log = LoggerFactory.getLogger(TestCardRegistry::class.java)

    /** Shared repository for all tests. */
    val repo = InMemoryCardRepository()

    /** Default deck card names (GameBridge.DEFAULT_DECK). */
    private val DEFAULT_DECK_CARDS = listOf(
        "Forest",
        "Llanowar Elves",
        "Elvish Mystic",
        "Giant Growth",
        "Mountain",
        "Raging Goblin",
    )

    /**
     * Auto-register a card by name. If already in repo, returns existing grpId.
     * Otherwise derives CardData from Forge's in-memory CardRules and registers it.
     * Returns the grpId (synthetic, 0 on failure).
     */
    fun ensureCardRegistered(cardName: String): Int {
        repo.findGrpIdByName(cardName)?.let { return it }

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
        repo.registerData(cardData, cardName)
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
        val failures = mutableListOf<String>()
        for (name in names) {
            val grpId = ensureCardRegistered(name)
            if (grpId == 0) failures.add(name)
        }
        check(failures.isEmpty()) {
            "Cards not found in Forge DB (grpId=0): ${failures.joinToString()}. " +
                "Use `just card-grp \"<name>\"` to verify card names."
        }
    }

    /**
     * Register all default deck cards.
     * Idempotent — safe to call from multiple test setup methods.
     */
    fun ensureRegistered() {
        if (repo.registeredCount > 0) return
        for (name in DEFAULT_DECK_CARDS) {
            ensureCardRegistered(name)
        }
    }
}
