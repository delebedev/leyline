package forge.nexus.bridge

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolve a resource path relative to the project root, trying
 * `base/<subPath>`, `../base/<subPath>`, and `../../base/<subPath>`.
 */
fun resolveForgeResource(
    subPath: String,
    check: (Path) -> Boolean = { Files.exists(it) },
): Path {
    val base = Paths.get("").toAbsolutePath()
    val candidates = listOf(
        base.resolve(subPath),
        base.resolve("../$subPath"),
        base.resolve("../../$subPath"),
    )
    return candidates.firstOrNull(check) ?: candidates.first()
}
