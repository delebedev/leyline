package leyline

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private fun File.walkUpFind(pred: (File) -> Boolean): File? {
    var dir = parentFile
    while (dir != null) {
        if (pred(dir)) return dir
        dir = dir.parentFile
    }
    return null
}

/**
 * Filesystem paths for recording/debug artifacts.
 *
 * Recordings live under `<project-root>/recordings/` (persistent, gitignored).
 * Each server start creates a timestamped session directory with `latest` symlink.
 *
 * Session structure:
 * ```
 * recordings/
 *   2026-02-22_14-30-00/
 *     mode.txt          # "engine" or "proxy"
 *     engine/           # GRE messages (both modes)
 *     capture/          # proxy mode only
 *       payloads/
 *       frames/
 *     events.jsonl      # paired event stream (SessionRecorder)
 *     analysis.json     # post-game analysis (SessionAnalyzer)
 *   latest -> 2026-02-22_14-30-00
 * ```
 */
object LeylinePaths {
    @Volatile
    var sessionTag: String = newSessionTag()
        private set

    /** Project-local recordings root (persistent, gitignored). */
    val RECORDINGS: File = detectRecordingsRoot()

    val SESSION_DIR: File get() = File(RECORDINGS, sessionTag)
    val ENGINE_DUMP: File get() = File(SESSION_DIR, "engine")
    val CAPTURE_ROOT: File get() = File(SESSION_DIR, "capture")
    val CAPTURE_PAYLOADS: File get() = File(CAPTURE_ROOT, "payloads")
    val CAPTURE_FRAMES: File get() = File(CAPTURE_ROOT, "frames")
    val FD_FRAMES_JSONL: File get() = File(CAPTURE_ROOT, "fd-frames.jsonl")
    val WAS_FRAMES_JSONL: File get() = File(SESSION_DIR, "was-frames.jsonl")
    val EVENTS_JSONL: File get() = File(SESSION_DIR, "events.jsonl")
    val ANALYSIS_JSON: File get() = File(SESSION_DIR, "analysis.json")
    val MODE_TXT: File get() = File(SESSION_DIR, "mode.txt")
    val MANIFEST_JSON: File = File(RECORDINGS, "manifest.json")

    private fun newSessionTag(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))

    /** Rotate to a fresh session directory and update the latest symlink. */
    fun rotateSession() {
        sessionTag = newSessionTag()
        updateLatestLink()
    }

    /** Create session dir and update latest symlink for the current session. */
    fun updateLatestLink() {
        SESSION_DIR.mkdirs()
        createLatestSymlink(RECORDINGS, sessionTag)
    }

    /** Create a relative `latest` symlink in [recordingsDir] pointing to [tag]. */
    internal fun createLatestSymlink(recordingsDir: File, tag: String) {
        val link = File(recordingsDir, "latest").toPath()
        try {
            // Remove existing link/dir — deleteIfExists handles symlinks;
            // if it's a non-symlink directory (legacy bug), delete it too.
            if (java.nio.file.Files.isSymbolicLink(link)) {
                java.nio.file.Files.deleteIfExists(link)
            } else if (java.nio.file.Files.isDirectory(link)) {
                link.toFile().deleteRecursively()
            }
            // Use relative target so the symlink works when the repo moves.
            java.nio.file.Files.createSymbolicLink(link, java.nio.file.Path.of(tag))
        } catch (_: Exception) {}
    }

    // Initialize on first load
    init {
        updateLatestLink()
    }

    /**
     * Detect project-local recordings root.
     *
     * Looks for a `recordings/` directory in CWD (standalone repo layout).
     * Falls back to `/tmp/arena-recordings` as last resort.
     */
    private fun detectRecordingsRoot(): File {
        val cwd = File(System.getProperty("user.dir", ".")).absoluteFile

        // Standalone repo: CWD is the project root
        if (File(cwd, "recordings").isDirectory || File(cwd, "src").isDirectory) {
            return File(cwd, "recordings")
        }

        // Last resort — /tmp/ (ephemeral)
        return File("/tmp/arena-recordings")
    }
}
