package leyline.acceptance

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Resolves acceptance data from one explicit repository root. */
internal object AcceptancePaths {
    private val root = Paths.get(System.getProperty("leyline.content.root", ".")).toAbsolutePath().normalize()

    fun resolveOrNull(
        relative: String,
        exists: (Path) -> Boolean = Files::exists,
    ): Path? = candidates(relative).firstOrNull(exists)

    fun resolve(
        relative: String,
        notFoundMessage: String = "$relative not found",
        exists: (Path) -> Boolean = Files::exists,
    ): Path =
        resolveOrNull(relative, exists)
            ?: error("$notFoundMessage at ${root.resolve(relative)}")

    private fun candidates(relative: String): List<Path> = listOf(root.resolve(relative))
}
