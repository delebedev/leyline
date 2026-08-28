package leyline.bridge.types

import forge.game.GameEntity
import forge.game.card.Card
import forge.game.player.Player
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

/**
 * Shared candidate-ref builder for `PromptRequest.candidateRefs`/`unfilteredRefs` construction.
 * Cards carry their current zone; players have no zone. Entities that are neither are dropped.
 */
fun Iterable<GameEntity>.toCandidateRefs(): List<PromptCandidateRefDto> =
    mapIndexedNotNull { index, entity ->
        when (entity) {
            is Card -> PromptCandidateRefDto(index, PromptCandidateKind.Card, entity.id, entity.zone?.zoneType?.name)
            is Player -> PromptCandidateRefDto(index, PromptCandidateKind.Player, entity.id)
            else -> null
        }
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
