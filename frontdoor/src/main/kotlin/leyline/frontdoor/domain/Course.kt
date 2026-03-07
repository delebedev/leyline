package leyline.frontdoor.domain

@JvmInline value class CourseId(val value: String)

/**
 * Client-visible course state machine modules.
 *
 * Sealed event lifecycle: `DeckSelect → CreateMatch ⇄ MatchResults → Complete`.
 * Quick Draft lifecycle: `BotDraft → DeckSelect → CreateMatch ⇄ MatchResults → Complete`.
 * Constructed events skip straight to `CreateMatch`. The remaining variants
 * (`Join`, `Sealed`, `GrantCardPool`, `RankUpdate`, `ClaimPrize`) exist in
 * the real server protocol but are not yet used by our implementation.
 *
 * **Invisible constraint:** the client uses [wireName] to drive UI transitions.
 * `DeckSelect` shows the sealed deck builder; `CreateMatch` shows the "Play"
 * button; `Complete` shows the event as finished. Sending the wrong module
 * for the current state will confuse the client UI.
 */
enum class CourseModule {
    Join,
    Sealed,
    GrantCardPool,
    BotDraft,
    DeckSelect,
    CreateMatch,
    MatchResults,
    RankUpdate,
    Complete,
    ClaimPrize,
    ;

    fun wireName(): String = name
}

data class CollationPool(val collationId: Int, val cardPool: List<Int>)

data class CourseDeck(
    val deckId: DeckId,
    val mainDeck: List<DeckCard>,
    val sideboard: List<DeckCard>,
)

data class CourseDeckSummary(
    val deckId: DeckId,
    val name: String,
    val tileId: Int,
    val format: String,
)

data class Course(
    val id: CourseId,
    val playerId: PlayerId,
    val eventName: String,
    val module: CourseModule,
    val wins: Int = 0,
    val losses: Int = 0,
    val cardPool: List<Int> = emptyList(),
    val cardPoolByCollation: List<CollationPool> = emptyList(),
    val deck: CourseDeck? = null,
    val deckSummary: CourseDeckSummary? = null,
)
