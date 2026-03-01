package forge.nexus.bridge

import kotlinx.serialization.Serializable

@Serializable
data class PromptCandidateRefDto(
    val index: Int, // maps to options[index]
    val kind: String, // "card" | "player"
    val entityId: Int, // game entity ID
    val zone: String? = null,
)

@Serializable
data class TargetDto(
    val kind: String, // "player" or "card"
    val id: Int,
)

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
