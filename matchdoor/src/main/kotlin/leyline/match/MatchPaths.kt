package leyline.match

import java.io.File

/** Resolve the project root directory (contains `puzzles/`, `justfile`, etc.). */
internal fun findLeylineDir(): File {
    val cwd = File(System.getProperty("user.dir"))
    if (File(cwd, "puzzles").isDirectory || File(cwd, "justfile").exists()) return cwd
    return cwd
}
