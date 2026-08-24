package leyline.game.generator

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Enumerates the on-disk `.pzl` puzzle library and exposes lightweight metadata
 * for listing (e.g. a public puzzle browser). Parsing reuses [PuzzleSource];
 * no DB or full [forge.gamemodes.puzzle.Puzzle] construction.
 *
 * The puzzle root is supplied explicitly (the composition root passes the
 * resolved content root); no ambient directory search happens here.
 */
object PuzzleCatalog {
    private val log = LoggerFactory.getLogger(PuzzleCatalog::class.java)

    data class Entry(
        val filename: String,
        val name: String,
        val goal: String?,
        val turns: Int?,
        val difficulty: String?,
        val description: String?,
    )

    /** List puzzles sorted by display name. Returns empty if the directory is absent. */
    fun list(dir: File): List<Entry> {
        if (!dir.isDirectory) {
            log.warn("Puzzle directory not found: {}", dir.absolutePath)
            return emptyList()
        }
        val files = dir.listFiles { f -> f.isFile && f.extension == "pzl" } ?: return emptyList()
        return files
            .mapNotNull { file -> runCatching { toEntry(file) }.getOrNull() }
            .sortedBy { it.name.lowercase() }
    }

    private fun toEntry(file: File): Entry {
        val meta = PuzzleSource.parseMetadata(file.readText())
        return Entry(
            filename = file.nameWithoutExtension,
            name = meta.name,
            goal = meta.goal,
            turns = meta.turns,
            difficulty = meta.difficulty,
            description = meta.description,
        )
    }
}
