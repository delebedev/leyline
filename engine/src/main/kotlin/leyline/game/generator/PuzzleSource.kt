package leyline.game.generator

import forge.gamemodes.puzzle.Puzzle
import forge.util.FileSection
import leyline.bridge.bootstrap.GameBootstrap
import leyline.config.PuzzleDefinition

/**
 * Loads Forge `.pzl` puzzles from text or a classpath resource.
 *
 * Lightweight: no DB dependency (unlike forge-web's PuzzleLoader which uses Exposed).
 * Parses via Forge's [FileSection.parseSections] and constructs a [Puzzle] object.
 */
object PuzzleSource {
    /** Keep raw puzzle identity and content independent of Forge. */
    fun definitionFromText(
        content: String,
        name: String = "inline",
    ): PuzzleDefinition = PuzzleDefinition(name, content)

    /** Parse a puzzle definition into Forge after localization is ready. */
    fun load(definition: PuzzleDefinition): Puzzle {
        GameBootstrap.initializeLocalization()
        val content = definition.content
        val lines = content.lines()
        val sections = FileSection.parseSections(lines)
        return Puzzle(sections, definition.identity, false)
    }

    /** Parse a puzzle from raw `.pzl` content string. */
    fun loadFromText(
        content: String,
        name: String = "inline",
    ): Puzzle = load(definitionFromText(content, name))

    /** Create a definition from a classpath resource (normally a test-private fixture). */
    fun definitionFromResource(resourcePath: String): PuzzleDefinition {
        if (resourcePath.startsWith("data/puzzles/")) {
            return PuzzleLibrary.fromConfiguredContentRoot().require(resourcePath.removePrefix("data/puzzles/"))
        }
        val stream =
            PuzzleSource::class.java.classLoader.getResourceAsStream(resourcePath)
                ?: error("Puzzle resource not found: $resourcePath")
        val content = stream.bufferedReader().use { it.readText() }
        val name = resourcePath.substringAfterLast('/').removeSuffix(".pzl")
        return definitionFromText(content, name)
    }

    /** Load a puzzle from a classpath resource (e.g. test resources). */
    fun loadFromResource(resourcePath: String): Puzzle = load(definitionFromResource(resourcePath))

    /**
     * Extract metadata from `.pzl` content without constructing a full [Puzzle].
     * Useful for display/logging.
     */
    fun parseMetadata(content: String): PuzzleMetadata {
        val lines = content.lines()
        val sections = FileSection.parseSections(lines)
        val meta = sections["metadata"] ?: emptyList()
        var name: String? = null
        var goal: String? = null
        var turns: Int? = null
        var difficulty: String? = null
        var description: String? = null
        for (line in meta) {
            val parts = line.split(":", limit = 2)
            if (parts.size < 2) continue
            when (parts[0].trim().lowercase()) {
                "name" -> name = parts[1].trim()
                "goal" -> goal = parts[1].trim()
                "turns" -> turns = parts[1].trim().toIntOrNull()
                "difficulty" -> difficulty = parts[1].trim().ifBlank { null }
                "description" -> description = parts[1].trim().ifBlank { null }
            }
        }
        return PuzzleMetadata(
            name = name ?: "Unknown Puzzle",
            goal = goal,
            turns = turns,
            difficulty = difficulty,
            description = description,
        )
    }

    data class PuzzleMetadata(
        val name: String,
        val goal: String?,
        val turns: Int?,
        val difficulty: String?,
        val description: String? = null,
    )
}
