package leyline.frontdoor.domain

@JvmInline value class DeckId(val value: String)

@JvmInline value class PlayerId(val value: String)

enum class Format {
    Standard,
    Historic,
    Explorer,
    Timeless,
    Alchemy,
    Brawl,
    ;

    companion object {
        fun fromString(s: String): Format =
            entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: Standard
    }
}

data class DeckCard(val grpId: Int, val quantity: Int)

data class Deck(
    val id: DeckId,
    val playerId: PlayerId,
    val name: String,
    val format: Format,
    val tileId: Int,
    val mainDeck: List<DeckCard>,
    val sideboard: List<DeckCard>,
    val commandZone: List<DeckCard>,
    val companions: List<DeckCard>,
) {
    val totalCards: Int get() = mainDeck.sumOf { it.quantity }
}
