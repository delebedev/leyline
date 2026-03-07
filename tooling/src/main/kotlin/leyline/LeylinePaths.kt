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
    val sessionTag: String = LocalDateTime.now()
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))

    /** Project-local recordings root (persistent, gitignored). */
    val RECORDINGS: File = detectRecordingsRoot()

    val SESSION_DIR: File = File(RECORDINGS, sessionTag)
    val ENGINE_DUMP: File = File(SESSION_DIR, "engine")
    val CAPTURE_ROOT: File = File(SESSION_DIR, "capture")
    val CAPTURE_PAYLOADS: File = File(CAPTURE_ROOT, "payloads")
    val CAPTURE_FRAMES: File = File(CAPTURE_ROOT, "frames")
    val FD_FRAMES_JSONL: File = File(CAPTURE_ROOT, "fd-frames.jsonl")
    val WAS_FRAMES_JSONL: File = File(SESSION_DIR, "was-frames.jsonl")
    val EVENTS_JSONL: File = File(SESSION_DIR, "events.jsonl")
    val ANALYSIS_JSON: File = File(SESSION_DIR, "analysis.json")
    val MODE_TXT: File = File(SESSION_DIR, "mode.txt")
    val MANIFEST_JSON: File = File(RECORDINGS, "manifest.json")

    /** Symlink latest session for quick access. */
    val LATEST: File = File(RECORDINGS, "latest").also { link ->
        SESSION_DIR.mkdirs()
        link.delete()
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), SESSION_DIR.toPath())
        } catch (_: Exception) {}
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
