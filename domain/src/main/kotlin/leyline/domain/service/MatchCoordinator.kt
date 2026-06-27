package leyline.domain.service

/**
 * Cross-boundary coordinator between Front Door (lobby) and Match Door (engine).
 *
 * FD writes selections during the lobby flow; MD reads them when the client
 * connects on port 30003. The client's connection sequence guarantees FD
 * writes complete before MD reads — no stronger sync than @Volatile needed.
 *
 * Interface lives in domain because both native and web match launches need it.
 * Implementation lives in app/ where services are composed.
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

    /** Resolve two player decks for AI-vs-AI spectator games. */
    fun resolveRandomDeckPairJson(): Pair<String, String>? = null

    /** Resolve the first available deck (fallback when client doesn't send deckId). */
    fun resolveFirstDeck(): String? = null

    /**
     * Resolve a pod-bot opponent deck for the given event. Returns null if the
     * event has no completed draft pod (caller should fall back to other paths).
     *
     * Quick Draft uses this so the match opponent is one of the 7 bots that drafted
     * alongside the player. Bot selection rotates per match in the course so a
     * second match faces a different bot than the first.
     */
    fun resolveOpponentDeckJson(eventName: String): String? = null

    // --- MD writes back (match result) ---

    /** Record match outcome. Called from MatchSession when game ends. */
    fun reportMatchResult(won: Boolean)

    /** Record match outcome for matchId-keyed web launches. */
    fun reportMatchResult(
        matchId: String,
        won: Boolean,
    ) = reportMatchResult(won)

    companion object {
        /** No-op implementation for tests and modes without a game engine. */
        val NOOP: MatchCoordinator =
            object : MatchCoordinator {
                override fun selectDeck(deckId: String) {}

                override fun selectEvent(eventName: String) {}

                override val selectedDeckId: String? = null
                override val selectedEventName: String? = null

                override fun resolveDeckJson(deckId: String): String? = null

                override fun resolveDeckJsonByName(name: String): String? = null

                override fun resolveRandomDeckPairJson(): Pair<String, String>? = null

                override fun resolveOpponentDeckJson(eventName: String): String? = null

                override fun reportMatchResult(won: Boolean) {}
            }
    }
}
