package leyline.game.generator

import forge.StaticData
import forge.util.FileSection
import kotlinx.serialization.Serializable
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.config.PuzzleDefinition
import leyline.game.data.CardRepository
import leyline.game.state.GameBridge

@Serializable
enum class PuzzleValidationStatus { Loaded, Invalid, Unsupported, EngineFailure }

@Serializable
data class PuzzleValidationResult(
    val status: PuzzleValidationStatus,
    val issues: List<String> = emptyList(),
    val name: String? = null,
    val goal: String? = null,
    val turns: Int? = null,
    val inputCardCount: Int = 0,
)

/** Validates a bounded candidate format, then applies it through the ordinary puzzle engine. */
class PuzzleValidation(
    private val cards: CardRepository,
) {
    fun validate(definition: PuzzleDefinition): PuzzleValidationResult {
        if (definition.content.length > MAX_TEXT_LENGTH) return invalid("Puzzle text exceeds $MAX_TEXT_LENGTH characters")
        val sections = FileSection.parseSections(definition.content.lines())
        if (sections.keys != setOf("metadata", "state")) return invalid("Expected exactly [metadata] and [state] sections")
        val metadata = entries(sections.getValue("metadata"), ':') ?: return invalid("Malformed or duplicate metadata")
        val state = entries(sections.getValue("state"), '=') ?: return invalid("Malformed or duplicate state field")
        if (metadata.keys.any { it !in METADATA_FIELDS }) return unsupported("Unsupported metadata field")
        val name = metadata["name"]?.takeIf(String::isNotBlank) ?: return invalid("Name is required")
        val goal = metadata["goal"] ?: return invalid("Goal is required")
        if (goal.lowercase() !in GOALS) return unsupported("Goal '$goal' is not supported by candidate validation")
        val turns = metadata["turns"]?.toIntOrNull()?.takeIf { it in 1..99 } ?: return invalid("Turns must be an integer from 1 to 99")
        if (state["activeplayer"]?.lowercase() !in setOf("human", "p0")) return unsupported("ActivePlayer must be Human")
        if (state["activephase"]?.lowercase() != "main1") return unsupported("ActivePhase must be Main1")
        if (state["turn"] != null && state["turn"] != "1") return unsupported("Candidate starting turn must be 1")

        GameBootstrap.initializeCardDatabase(quiet = true)
        var count = 0
        val seen = mutableSetOf<String>()
        for ((key, value) in state) {
            if (key in GLOBAL_FIELDS) continue
            val field = PLAYER_FIELD.matchEntire(key) ?: return unsupported("Unsupported state field: $key")
            val (_, player, property) = field.groupValues
            val seat = if (player == "human" || player == "p0") "p0" else "p1"
            if (!seen.add("$seat$property")) return invalid("Duplicate state field for $seat: $property")
            when (property) {
                "life" -> if (value.toIntOrNull()?.let { it in 1..10_000 } != true) return invalid("$key must be a positive life total")
                "landsplayed" ->
                    if (value.toIntOrNull()?.let { it in 0..100 } !=
                        true
                    ) {
                        return invalid("$key must be an integer from 0 to 100")
                    }
                "manapool", "persistentmana" -> {
                    if (value.isNotBlank() && value.split(' ').any { it !in MANA }) return unsupported("Unsupported mana in $key")
                }
                else -> {
                    for (entry in value.split(';').filter(String::isNotBlank)) {
                        count += 1
                        if (count > MAX_CARDS) return invalid("Puzzle contains more than $MAX_CARDS cards")
                        val card = entry.trim().split('|')
                        if (card.drop(1).any { it !in CARD_FLAGS }) return unsupported("Unsupported card setup: $entry")
                        if (cards.findGrpIdByName(card.first()) == null ||
                            StaticData.instance().commonCards.getCard(card.first()) == null
                        ) {
                            return invalid("Unknown card: ${card.first()}")
                        }
                    }
                }
            }
        }
        if (seen.none { it.startsWith("p0") } || seen.none { it.startsWith("p1") }) {
            return invalid("State must define both Human and AI player positions")
        }
        if (count == 0) return invalid("At least one card is required")
        val bridge = GameBridge(cardRepository = cards)
        return try {
            bridge.startStaticPuzzle(PuzzleSource.load(definition), SeatId(1), beforeRuntimeStart = {})
            PuzzleValidationResult(PuzzleValidationStatus.Loaded, name = name, goal = goal, turns = turns, inputCardCount = count)
        } catch (failure: Exception) {
            PuzzleValidationResult(PuzzleValidationStatus.EngineFailure, listOf(failure.message ?: failure.javaClass.simpleName))
        } finally {
            bridge.teardownResources()
        }
    }

    private fun entries(
        lines: List<String>,
        separator: Char,
    ): Map<String, String>? {
        val result = linkedMapOf<String, String>()
        for (line in lines.filter { it.isNotBlank() && !it.startsWith('#') }) {
            val parts = line.split(separator, limit = 2)
            if (parts.size != 2 || parts[0] != parts[0].trim()) return null
            if (result.put(parts[0].lowercase(), parts[1].trim()) != null) return null
        }
        return result
    }

    private fun invalid(reason: String) = PuzzleValidationResult(PuzzleValidationStatus.Invalid, listOf(reason))

    private fun unsupported(reason: String) = PuzzleValidationResult(PuzzleValidationStatus.Unsupported, listOf(reason))

    companion object {
        private const val MAX_TEXT_LENGTH = 32_768
        private const val MAX_CARDS = 100
        private val METADATA_FIELDS = setOf("name", "goal", "turns", "difficulty", "description")
        private val GOALS = setOf("win", "survive", "win before opponent's next turn")
        private val GLOBAL_FIELDS = setOf("activeplayer", "activephase", "turn")
        private val PLAYER_FIELD =
            Regex("(human|ai|p[01])(life|landsplayed|manapool|persistentmana|hand|battlefield|library|graveyard|exile|command|sideboard)")
        private val CARD_FLAGS = setOf("Tapped", "SummonSick")
        private val MANA = setOf("W", "U", "B", "R", "G", "C")
    }
}
