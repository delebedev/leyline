package leyline.domain.service

import leyline.domain.deck.DeckCards

/**
 * Cross-boundary coordinator between Front Door (lobby) and Match Door (engine).
 *
 * FD writes selections during the lobby flow; MD reads them when the client
 * connects on port 30003. The client's connection sequence guarantees FD
 * writes complete before MD reads — no stronger sync than @Volatile needed.
 *
 * The repository-backed implementation lives beside this interface so native
 * and embedding hosts share one launch and result policy.
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
     * Resolve a deck to its typed card sections (main + sideboard + command zone).
     * Tries DeckRepository first, falls back to CourseService for sealed events.
     */
    fun resolveDeckCards(deckId: String): DeckCards?

    /** Resolve a deck by name (AI deck from config). */
    fun resolveDeckCardsByName(name: String): DeckCards?

    /** Resolve two player decks for AI-vs-AI spectator games. */
    fun resolveRandomDeckCardsPair(): Pair<DeckCards, DeckCards>? = null

    /** Resolve the first available deck (fallback when client doesn't send deckId). */
    fun resolveFirstDeckCards(): DeckCards? = null

    /**
     * Resolve a pod-bot opponent deck for the given event. Returns null if the
     * event has no completed draft pod (caller should fall back to other paths).
     *
     * Quick Draft uses this so the match opponent is one of the 7 bots that drafted
     * alongside the player. Bot selection rotates per match in the course so a
     * second match faces a different bot than the first.
     */
    fun resolveOpponentDeckCards(eventName: String): DeckCards? = null

    // --- MD writes back (match result) ---

    /** Record match outcome. Called from MatchSession when game ends. */
    fun reportMatchResult(won: Boolean)

    companion object {
        /** No-op implementation for tests and modes without a game engine. */
        val NOOP: MatchCoordinator =
            object : MatchCoordinator {
                override fun selectDeck(deckId: String) {}

                override fun selectEvent(eventName: String) {}

                override val selectedDeckId: String? = null
                override val selectedEventName: String? = null

                override fun resolveDeckCards(deckId: String): DeckCards? = null

                override fun resolveDeckCardsByName(name: String): DeckCards? = null

                override fun resolveRandomDeckCardsPair(): Pair<DeckCards, DeckCards>? = null

                override fun resolveOpponentDeckCards(eventName: String): DeckCards? = null

                override fun reportMatchResult(won: Boolean) {}
            }
    }
}
