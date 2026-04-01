package leyline.bridge

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolve a resource path relative to the project root.
 *
 * In bundle mode (`-Dleyline.res.dir=<path>`), maps forge submodule paths to the
 * flat bundle layout. In dev mode, searches relative to cwd.
 *
 * Bundle layout:  `<bundle>/res/cardsfolder/`, `<bundle>/res/languages/`
 * Dev layout:     `forge/forge-gui/res/cardsfolder/`, `forge/forge-gui/res/languages/`
 */
fun resolveForgeResource(
    subPath: String,
    check: (Path) -> Boolean = { Files.exists(it) },
): Path {
    val base = Paths.get("").toAbsolutePath()
    val candidates = buildList {
        // Bundle layout: -Dleyline.res.dir → <bundle>/res/
        // Callers pass "forge-gui/res/languages" or "forge-gui" — map both.
        val bundleResDir = System.getProperty("leyline.res.dir")
        if (bundleResDir != null) {
            val resPath = Paths.get(bundleResDir)
            when {
                // "forge-gui/res/X" → res/X
                subPath.startsWith("forge-gui/res/") ->
                    add(resPath.resolve(subPath.removePrefix("forge-gui/res/")))
                // "forge-gui" → parent of res/ (so .resolve("res") works)
                subPath == "forge-gui" ->
                    add(resPath.parent ?: resPath)
            }
        }
        // Dev layout: relative to cwd
        add(base.resolve(subPath))
        add(base.resolve("forge/$subPath"))
        add(base.resolve("../$subPath"))
        add(base.resolve("../forge/$subPath"))
        add(base.resolve("../../$subPath"))
    }
    return candidates.firstOrNull(check) ?: candidates.first()
}
