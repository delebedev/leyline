package leyline.match

import java.io.File

/** Resolve the project root directory (contains `puzzles/`, `justfile`, etc.). */
internal fun findLeylineDir(): File {
    val cwd = File(System.getProperty("user.dir"))
    var candidate: File? = cwd
    repeat(6) {
        val dir = candidate ?: return cwd
        if (File(dir, "puzzles").isDirectory || File(dir, "justfile").exists()) return dir
        candidate = dir.parentFile
    }
    return cwd
}
