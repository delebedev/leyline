package leyline.acceptance

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves a path given relative to the module dir, one level up, or two levels up — covers
 * running from the module root vs. the repo root depending on how the test task is invoked.
 */
internal object AcceptancePaths {
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
            ?: error("$notFoundMessage in ${candidates(relative)} (cwd=${Paths.get("").toAbsolutePath()})")

    private fun candidates(relative: String): List<Path> =
        listOf(Paths.get(relative), Paths.get("../$relative"), Paths.get("../../$relative"))
}
