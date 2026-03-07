package leyline.frontdoor.domain

@JvmInline value class CourseId(val value: String)

enum class CourseModule {
    Join,
    Sealed,
    GrantCardPool,
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
