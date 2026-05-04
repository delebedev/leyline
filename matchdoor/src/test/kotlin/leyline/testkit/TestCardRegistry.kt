package leyline.testkit

import leyline.game.InMemoryCardRepository

/**
 * Registers test deck cards in the shared [InMemoryCardRepository].
 *
 * Routes through [FixtureCardLoader]: client identity (grpId, ability ids,
 * tokens, linked faces) comes from per-card YAML fixtures under
 * `matchdoor/src/test/resources/test-cards/`; rules data (P/T, types, mana,
 * etc.) is derived from Forge's `CardRules` at test startup. No SQLite needed.
 */
object TestCardRegistry {
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
     * Register a card by name. Idempotent. Thin wrapper over
     * [FixtureCardLoader.ensureCardRegistered] (which owns the
     * Forge-static-data mutex).
     */
    fun ensureCardRegistered(cardName: String): Int = FixtureCardLoader.ensureCardRegistered(repo, cardName)

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
     * Register all puzzle cards after `GameBridge.startPuzzle`. Walks every
     * zone of every player and routes each card name through
     * [FixtureCardLoader]. Production doesn't need this — card data is in
     * SQLite.
     */
    fun registerPuzzleCards(game: forge.game.Game) {
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
                    FixtureCardLoader.ensureCardRegistered(repo, card.name)
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
