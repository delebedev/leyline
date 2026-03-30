package leyline

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Suppress("UnusedPrivateMember")
private fun File.walkUpFind(pred: (File) -> Boolean): File? {
    var dir = parentFile
    while (dir != null) {
        if (pred(dir)) return dir
        dir = dir.parentFile
    }
    return null
}

/**
 * Filesystem paths for debug/recording artifacts.
 *
 * Each server start creates a timestamped session directory under
 * `<project-root>/recordings/` with a `latest` symlink. Engine proto
 * dumps land in `<session>/engine/`.
 */
object LeylinePaths {
    @Volatile
    var sessionTag: String = newSessionTag()
        private set

    val RECORDINGS: File = detectRecordingsRoot()
    val SESSION_DIR: File get() = File(RECORDINGS, sessionTag)
    val ENGINE_DUMP: File get() = File(SESSION_DIR, "engine")

    private fun newSessionTag(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))

    fun rotateSession() {
        sessionTag = newSessionTag()
        updateLatestLink()
    }

    fun updateLatestLink() {
        SESSION_DIR.mkdirs()
        createLatestSymlink(RECORDINGS, sessionTag)
    }

    internal fun createLatestSymlink(recordingsDir: File, tag: String) {
        val link = File(recordingsDir, "latest").toPath()
        try {
            if (java.nio.file.Files.isSymbolicLink(link)) {
                java.nio.file.Files.deleteIfExists(link)
            } else if (java.nio.file.Files.isDirectory(link)) {
                link.toFile().deleteRecursively()
            }
            java.nio.file.Files.createSymbolicLink(link, java.nio.file.Path.of(tag))
        } catch (_: Exception) {}
    }

    init {
        updateLatestLink()
    }

    private fun detectRecordingsRoot(): File {
        val cwd = File(System.getProperty("user.dir", ".")).absoluteFile
        val projectRoot = if (File(cwd, "settings.gradle.kts").isFile) {
            cwd
        } else {
            cwd.walkUpFind { File(it, "settings.gradle.kts").isFile }
        }
        if (projectRoot != null) return File(projectRoot, "recordings")
        return File("/tmp/arena-recordings")
    }
}
