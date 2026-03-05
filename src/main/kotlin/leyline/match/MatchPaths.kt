package leyline.match

import java.io.File

/** Resolve the project root directory (contains `decks/`, `puzzles/`, etc.). */
internal fun findLeylineDir(): File {
    val cwd = File(System.getProperty("user.dir"))
    if (File(cwd, "decks").isDirectory) return cwd
    return cwd
}
