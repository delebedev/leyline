package leyline.game.generator

import leyline.config.PuzzleDefinition
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Shared configured-root lookup for puzzle identities. */
class PuzzleLibrary(
    private val root: Path,
) {
    constructor(root: File) : this(root.toPath())

    companion object {
        /** Shared content adapter used by standalone tools and test resources. */
        fun fromConfiguredContentRoot(): PuzzleLibrary =
            PuzzleLibrary(
                Path
                    .of(System.getProperty("leyline.content.root", "."))
                    .resolve("data/puzzles")
                    .toAbsolutePath()
                    .normalize(),
            )
    }

    fun find(identity: String): PuzzleDefinition? {
        val normalized = normalize(identity) ?: return null
        val path = root.resolve("$normalized.pzl")
        if (!Files.isRegularFile(path)) return null
        return PuzzleDefinition(normalized, path.toFile().readText())
    }

    fun require(identity: String): PuzzleDefinition = find(identity) ?: error("Puzzle not found: $identity in ${root.toAbsolutePath()}")

    private fun normalize(identity: String): String? {
        val value = identity.removeSuffix(".pzl").trim()
        if (value.isEmpty() || value != Path.of(value).fileName.toString() || value == "." || value == "..") return null
        return value
    }
}
