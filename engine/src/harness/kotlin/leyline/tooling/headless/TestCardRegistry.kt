package leyline.tooling.headless

/**
 * Registers test cards in the shared in-memory repository.
 *
 * Routes through [FixtureCardLoader]: a card that still has a per-card YAML
 * fixture under `engine/src/test/resources/test-cards/` keeps its pinned
 * client identity, while every other card resolves through
 * [ForgeCatalogTestRepository]'s catalog-scoped identities. Rules data (P/T,
 * types, mana, etc.) comes from Forge's `CardRules` either way. No SQLite
 * needed.
 */
object TestCardRegistry {
    /**
     * Shared repository for all tests. Fixture-registered rows win; the
     * Forge catalog fills in every card without a fixture.
     */
    val repo = ForgeCatalogTestRepository()

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
     * Register the entire YAML fixture catalog into the shared repo.
     *
     * Puzzle runs pre-register the catalog before the puzzle applies, so
     * `GameBridge`'s puzzle-card lookups resolve client identity directly
     * from the in-memory repository — the one fixture repository used by
     * both constructed and puzzle harnesses. Idempotent per JVM; cards
     * without a fixture still fail clearly via [FixtureCardLoader].
     */
    fun ensureFixtureCatalogRegistered() {
        if (catalogRegistered) return
        synchronized(this) {
            if (catalogRegistered) return
            for (name in leyline.game.data.TestCardFixtures.all.keys) {
                ensureCardRegistered(name)
            }
            catalogRegistered = true
        }
    }

    private var catalogRegistered = false

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
                "Use `just card \"<name>\"` to verify card names."
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
                forge.game.zone.ZoneType.Sideboard,
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
