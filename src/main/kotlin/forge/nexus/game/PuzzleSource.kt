package forge.nexus.game

import forge.gamemodes.puzzle.Puzzle
import forge.gamemodes.puzzle.PuzzleIO
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Loads Forge `.pzl` puzzles from text, file, or classpath resource.
 *
 * Lightweight: no DB dependency (unlike forge-web's PuzzleLoader which uses Exposed).
 * Parses via Forge's [PuzzleIO.parsePuzzleSections] and constructs a [Puzzle] object.
 */
object PuzzleSource {
    private val log = LoggerFactory.getLogger(PuzzleSource::class.java)

    /** Parse a puzzle from raw `.pzl` content string. */
    fun loadFromText(content: String, name: String = "inline"): Puzzle {
        val lines = content.lines()
        val sections = PuzzleIO.parsePuzzleSections(lines)
        return Puzzle(sections, name, false)
    }

    /** Load a puzzle from a `.pzl` file on disk. */
    fun loadFromFile(path: String): Puzzle {
        val file = File(path)
        require(file.exists()) { "Puzzle file not found: $path" }
        val content = file.readText()
        val name = file.nameWithoutExtension
        log.info("Loaded puzzle from file: {} ({} chars)", path, content.length)
        return loadFromText(content, name)
    }

    /** Load a puzzle from a classpath resource (e.g. test resources). */
    fun loadFromResource(resourcePath: String): Puzzle {
        val stream = PuzzleSource::class.java.classLoader.getResourceAsStream(resourcePath)
            ?: error("Puzzle resource not found: $resourcePath")
        val content = stream.bufferedReader().readText()
        val name = resourcePath.substringAfterLast('/').removeSuffix(".pzl")
        log.info("Loaded puzzle from resource: {} ({} chars)", resourcePath, content.length)
        return loadFromText(content, name)
    }

    /**
     * Extract metadata from `.pzl` content without constructing a full [Puzzle].
     * Useful for display/logging.
     */
    fun parseMetadata(content: String): PuzzleMetadata {
        val lines = content.lines()
        val sections = PuzzleIO.parsePuzzleSections(lines)
        val meta = sections["metadata"] ?: emptyList()
        var name: String? = null
        var goal: String? = null
        var turns: Int? = null
        var difficulty: String? = null
        for (line in meta) {
            val parts = line.split(":", limit = 2)
            if (parts.size < 2) continue
            when (parts[0].trim().lowercase()) {
                "name" -> name = parts[1].trim()
                "goal" -> goal = parts[1].trim()
                "turns" -> turns = parts[1].trim().toIntOrNull()
                "difficulty" -> difficulty = parts[1].trim().ifBlank { null }
            }
        }
        return PuzzleMetadata(
            name = name ?: "Unknown Puzzle",
            goal = goal,
            turns = turns,
            difficulty = difficulty,
        )
    }

    data class PuzzleMetadata(
        val name: String,
        val goal: String?,
        val turns: Int?,
        val difficulty: String?,
    )
}
