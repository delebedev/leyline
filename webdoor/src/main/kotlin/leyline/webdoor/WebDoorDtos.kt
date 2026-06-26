package leyline.webdoor

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
    val packNumber: Int,
    val pickNumber: Int,
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
data class GreStartRequest(
    val matchId: String? = null,
    val wireMatchId: String? = null,
    val seat1Deck: String? = null,
    val seat2Deck: String? = null,
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
data class AuthView(
    val playerId: String?,
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
data class CardMetadataView(
    val cards: List<CardMetadataEntry> = emptyList(),
)

@Serializable
data class CardMetadataEntry(
    val grpId: Int,
    val name: String? = null,
)
