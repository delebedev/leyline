package leyline.frontdoor.service

/**
 * Cross-boundary coordinator between Front Door (lobby) and Match Door (engine).
 *
 * FD writes selections during the lobby flow; MD reads them when the client
 * connects on port 30003. The client's connection sequence guarantees FD
 * writes complete before MD reads — no stronger sync than @Volatile needed.
 *
 * Interface lives in :frontdoor because it speaks FD domain vocabulary.
 * Implementation lives in app/ where both modules are visible.
 */
interface MatchCoordinator {

    // --- FD writes (lobby flow) ---

    /** Client selected a deck (CmdType 612 / 622). */
    fun selectDeck(deckId: String)

    /** Client selected an event (CmdType 612 / 603). */
    fun selectEvent(eventName: String)

    // --- MD reads (match connect) ---

    /** Deck selected in the most recent lobby flow. */
    val selectedDeckId: String?

    /** Event selected in the most recent lobby flow. */
    val selectedEventName: String?

    /**
     * Resolve a deck to its JSON card list (MainDeck + Sideboard).
     * Tries DeckRepository first, falls back to CourseService for sealed events.
     */
    fun resolveDeckJson(deckId: String): String?

    /** Resolve a deck by name (AI deck from config). */
    fun resolveDeckJsonByName(name: String): String?

    // --- PvP queue ---

    /** Mark a matchId as PvP (two-human). Called by FD when queue pairs. */
    fun registerPvpMatch(matchId: String) {}

    /** Check if a matchId is a PvP match (uses startTwoPlayer on MD). */
    fun isPvpMatch(matchId: String): Boolean = false

    // --- MD writes back (match result) ---

    /** Record match outcome. Called from MatchSession when game ends. */
    fun reportMatchResult(won: Boolean)

    companion object {
        /** No-op implementation for tests and modes without a game engine. */
        val NOOP: MatchCoordinator = object : MatchCoordinator {
            override fun selectDeck(deckId: String) {}
            override fun selectEvent(eventName: String) {}
            override val selectedDeckId: String? = null
            override val selectedEventName: String? = null
            override fun resolveDeckJson(deckId: String): String? = null
            override fun resolveDeckJsonByName(name: String): String? = null
            override fun reportMatchResult(won: Boolean) {}
        }
    }
}
