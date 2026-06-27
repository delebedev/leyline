package leyline.bridge.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PromptCandidateKind {
    @SerialName("card")
    Card,

    @SerialName("player")
    Player,
}

@Serializable
data class PromptCandidateRefDto(
    val index: Int, // maps to options[index]
    val kind: PromptCandidateKind,
    val entityId: Int, // game entity ID
    val zone: String? = null,
) {
    fun isCard(): Boolean = kind == PromptCandidateKind.Card

    fun isPlayer(): Boolean = kind == PromptCandidateKind.Player
}

@Serializable
data class PromptChoiceDto(
    val promptId: String,
    val promptType: String, // "confirm" | "choose_cards" | "choose_one" | "choose_color" | "order"
    val message: String,
    val min: Int = 1,
    val max: Int = 1,
    val options: List<PromptOptionDto>,
    val candidateRefs: List<PromptCandidateRefDto> = emptyList(),
)

@Serializable
data class PromptOptionDto(
    val id: String,
    val label: String,
)

@Serializable
data class PlayerPhaseStopsDto(
    val playerId: Int,
    val enabled: List<String>,
)
