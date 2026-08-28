package leyline.config

import kotlinx.serialization.Serializable

/** Identity and source text for a puzzle, before Forge-specific construction. */
@Serializable
data class PuzzleDefinition(
    val identity: String,
    val content: String,
) {
    init {
        require(identity.isNotBlank()) { "Puzzle identity must not be blank" }
    }
}
