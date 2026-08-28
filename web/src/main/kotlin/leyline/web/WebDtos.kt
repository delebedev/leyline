package leyline.web

import kotlinx.serialization.Serializable

@Serializable
data class StartDraftRequest(
    val playerId: String,
    val eventName: String,
)

@Serializable
data class PickDraftRequest(
    val playerId: String,
    val eventName: String,
    val cardId: Int,
)

@Serializable
data class SubmitDeckRequest(
    val playerId: String,
    val eventName: String,
    val name: String = "Draft Deck",
    val deckId: String? = null,
    val mainDeck: List<WebDeckCard>,
    val sideboard: List<WebDeckCard> = emptyList(),
)

@Serializable
data class PlayDraftRequest(
    val playerId: String,
    val eventName: String,
)

@Serializable
data class WebDeckCard(
    val grpId: Int,
    val quantity: Int,
)

@Serializable
data class DraftSessionView(
    val eventName: String,
    val status: String,
    val packNumber: Int,
    val pickNumber: Int,
    val draftPack: List<Int>,
    val pickedCards: List<Int>,
)

@Serializable
data class CourseView(
    val eventName: String,
    val module: String,
    val wins: Int,
    val losses: Int,
    val cardPool: List<Int>,
    val deckId: String?,
)

@Serializable
data class DraftPlayResponse(
    val matchId: String,
    val wireMatchId: String,
)

@Serializable
data class PublicSpectatorResponse(
    val matchId: String,
    val wireMatchId: String,
    val seat1: PublicSeatView,
    val seat2: PublicSeatView,
)

@Serializable
data class PublicSeatView(
    val name: String,
)

@Serializable
data class ViewerCountView(
    val count: Int,
)

@Serializable
data class GreStartRequest(
    val matchId: String? = null,
    val wireMatchId: String? = null,
    val seat1Deck: String? = null,
    val seat2Deck: String? = null,
    val gameVariant: String? = null,
    val challengeId: String? = null,
    /** Explicit puzzle identity for dev/Acceptance/E2E launches only. */
    val puzzle: String? = null,
    val spectatorMode: Boolean? = null,
)

@Serializable
data class CreateDeckRequest(
    val playerId: String,
    val name: String,
    val format: String = "Standard",
    val mainDeck: List<WebDeckCard>,
    val sideboard: List<WebDeckCard> = emptyList(),
)

@Serializable
data class DeckView(
    val id: String,
    val playerId: String,
    val name: String,
    val format: String,
    val mainDeck: List<WebDeckCard>,
    val sideboard: List<WebDeckCard>,
)

@Serializable
data class CollectionView(
    val grpIds: List<Int>,
)

@Serializable
data class LimitedSetView(
    val code: String,
    val name: String,
    val date: String? = null,
    val type: String? = null,
    val cardCount: Int? = null,
    val description: String? = null,
    val mechanics: List<String>? = null,
    val archetypes: List<LimitedSetArchetypeView>? = null,
)

@Serializable
data class LimitedSetArchetypeView(
    val pair: String,
    val name: String,
    val strategy: String? = null,
)

@Serializable
data class AuthView(
    val playerId: String?,
    val guest: Boolean = false,
)

@Serializable
data class ChallengeSummary(
    val challengeId: String,
    val name: String,
)

@Serializable
data class RequestLoginCodeRequest(
    val email: String,
)

@Serializable
data class VerifyLoginCodeRequest(
    val email: String,
    val code: String,
)

@Serializable
data class LoginResponse(
    val playerId: String,
    val email: String,
)

@Serializable
data class GreCardMetaDto(
    val grpId: Int,
    val name: String? = null,
    val setCode: String? = null,
    val titleId: Int? = null,
    val manaCost: String? = null,
    val power: String? = null,
    val toughness: String? = null,
    val types: String? = null,
    val subtypes: String? = null,
    val imageUrl: String? = null,
    /** Evergreen keywords the card is printed with, for display as marks. */
    val keywords: List<String> = emptyList(),
)

@Serializable
data class DraftCardDto(
    val name: String,
    val grpId: Int? = null,
    val manaCost: String? = null,
    val typeLine: String? = null,
    val rarity: String? = null,
    val colors: List<String> = emptyList(),
    val setCode: String? = null,
    val collectorNumber: String? = null,
    val score: Double? = null,
)

@Serializable
data class ParseDecklistRequest(
    val text: String,
)

/** A fully resolved decklist card — the endpoint never returns an unresolved row. */
@Serializable
data class DecklistCardDto(
    val grpId: Int,
    val quantity: Int,
    val card: DraftCardDto,
)

/**
 * Success shape for `/api/cards/parse-decklist`. Command-zone and companion sections
 * are rejected (see [ParseDecklistErrorResponse]) until the Web deck editor can
 * persist them.
 */
@Serializable
data class ParseDecklistResponse(
    val mainboard: List<DecklistCardDto>,
    val sideboard: List<DecklistCardDto>,
)

/** Failure shape for `/api/cards/parse-decklist` — every parse/resolution failure, not just the first. */
@Serializable
data class ParseDecklistErrorResponse(
    val errors: List<String>,
)

@Serializable
data class CardMetadataView(
    val cards: List<CardMetadataEntry> = emptyList(),
)

@Serializable
data class CardMetadataEntry(
    val grpId: Int,
    val name: String? = null,
)
