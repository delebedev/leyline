package leyline.game.generator

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Enumerates the on-disk `.pzl` puzzle library and exposes lightweight metadata
 * for listing (e.g. a public puzzle browser). Parsing reuses [PuzzleSource];
 * no DB or full [forge.gamemodes.puzzle.Puzzle] construction.
 *
 * Directory resolution walks up from the working directory to find a `puzzles/`
 * folder so it works both from a module test cwd and a packaged launch cwd.
 * Override with the `LEYLINE_PUZZLE_DIR` env var.
 */
object PuzzleCatalog {
    private val log = LoggerFactory.getLogger(PuzzleCatalog::class.java)
    private const val WALK_UP_LIMIT = 6

    data class Entry(
        val filename: String,
        val name: String,
        val goal: String?,
        val turns: Int?,
        val difficulty: String?,
        val description: String?,
    )

    /** List puzzles sorted by display name. Returns empty if the directory is absent. */
    fun list(dir: File = defaultDir()): List<Entry> {
        if (!dir.isDirectory) {
            log.warn("Puzzle directory not found: {}", dir.absolutePath)
            return emptyList()
        }
        val files = dir.listFiles { f -> f.isFile && f.extension == "pzl" } ?: return emptyList()
        return files
            .mapNotNull { file -> runCatching { toEntry(file) }.getOrNull() }
            .sortedBy { it.name.lowercase() }
    }

    /** Resolve the puzzle directory: env override, then walk up for a `puzzles/` folder. */
    fun defaultDir(): File {
        System.getenv("LEYLINE_PUZZLE_DIR")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        var cwd: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(WALK_UP_LIMIT) {
            val candidate = cwd?.let { File(it, "puzzles") }
            if (candidate != null && candidate.isDirectory) return candidate
            cwd = cwd?.parentFile
        }
        return File(System.getProperty("user.dir"), "puzzles")
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
