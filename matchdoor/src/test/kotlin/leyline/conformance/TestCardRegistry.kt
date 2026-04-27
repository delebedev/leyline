package leyline.conformance

import leyline.game.InMemoryCardRepository
import org.slf4j.LoggerFactory

/**
 * Registers test deck cards in the shared [InMemoryCardRepository].
 *
 * Routes through [FixtureCardLoader]: Arena identity (grpId, ability ids,
 * tokens, linked faces) comes from per-card YAML fixtures under
 * `matchdoor/src/test/resources/test-cards/`; rules data (P/T, types, mana,
 * etc.) is derived from Forge's `CardRules` at test startup. No SQLite needed.
 */
object TestCardRegistry {
    private val log = LoggerFactory.getLogger(TestCardRegistry::class.java)

    /** Shared repository for all tests. */
    val repo = InMemoryCardRepository()

    /** Default deck card names (GameBridge.DEFAULT_DECK). */
    private val DEFAULT_DECK_CARDS =
        listOf(
            "Forest",
            "Llanowar Elves",
            "Elvish Mystic",
            "Giant Growth",
            "Mountain",
            "Raging Goblin",
        )

    /**
     * Register a card by name. Idempotent. Routes through [FixtureCardLoader],
     * which sources Arena identity from YAML fixtures under
     * `matchdoor/src/test/resources/test-cards/` and rules data (P/T, types,
     * mana, etc.) from Forge's `CardRules`. Errors loudly when no fixture
     * exists for a card Forge knows about; returns 0 silently for
     * engine-internal names that aren't in Forge either.
     */
    // Serialize card registration: Forge's StaticData.attemptToLoadCard mutates
    // static state. Concurrent Kotest specs would race on it.
    @Synchronized
    fun ensureCardRegistered(cardName: String): Int =
        FixtureCardLoader.ensureCardRegistered(repo, cardName)

    /**
     * Bulk-register all card names from a deck list string.
     * Parses "N CardName" lines, registers each unique name.
     */
    private val SECTION_HEADER = Regex("""^\[.+]$|^(Deck|Sideboard|Maybeboard|Commander|Companion)\s*$""", RegexOption.IGNORE_CASE)

    fun ensureDeckRegistered(deckList: String) {
        val names =
            deckList
                .trim()
                .lines()
                .filter { it.isNotBlank() }
                .map { it.trim() }
                .filter { !SECTION_HEADER.matches(it) }
                .map { it.replaceFirst(Regex("^\\d+\\s+"), "") }
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
     * Register all puzzle cards after [GameBridge.startPuzzle].
     *
     * Walks all zones in the game and derives synthetic [CardData] for each card
     * via [PuzzleCardRegistrar]. Production doesn't need this — card data is in SQLite.
     */
    fun registerPuzzleCards(game: forge.game.Game) {
        val registrar = leyline.game.PuzzleCardRegistrar(repo)
        val allZones =
            listOf(
                forge.game.zone.ZoneType.Hand,
                forge.game.zone.ZoneType.Battlefield,
                forge.game.zone.ZoneType.Library,
                forge.game.zone.ZoneType.Graveyard,
                forge.game.zone.ZoneType.Exile,
                forge.game.zone.ZoneType.Command,
            )
        for (player in game.players) {
            for (zone in allZones) {
                for (card in player.getZone(zone).cards) {
                    if (card.rules != null) {
                        registrar.ensureCardRegistered(card)
                    } else {
                        registrar.ensureCardRegisteredByName(card.name)
                    }
                }
            }
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
