package forge.nexus

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
 * Recordings live under `<project-root>/forge-nexus/recordings/` (persistent,
 * gitignored). Each server start creates a timestamped session directory with
 * `latest` symlink.
 *
 * Session structure:
 * ```
 * recordings/
 *   2026-02-22_14-30-00/
 *     mode.txt          # "engine" or "proxy" or "hybrid"
 *     engine/           # GRE messages (both modes)
 *     capture/          # proxy mode only
 *       payloads/
 *       frames/
 *     events.jsonl      # paired event stream (SessionRecorder)
 *     analysis.json     # post-game analysis (SessionAnalyzer)
 *   latest -> 2026-02-22_14-30-00
 * ```
 */
object NexusPaths {
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
     * Strategy: walk up from CWD looking for `forge-nexus/` directory
     * (same heuristic forge-gui uses for res/). Falls back to CWD-relative
     * `forge-nexus/recordings/` if already inside forge-nexus, or
     * `/tmp/arena-recordings` as last resort.
     */
    private fun detectRecordingsRoot(): File {
        // Try working directory first
        val cwd = File(System.getProperty("user.dir", ".")).absoluteFile

        // If CWD is forge-nexus or contains forge-nexus
        val nexusDir = when {
            cwd.name == "forge-nexus" -> cwd
            File(cwd, "forge-nexus").isDirectory -> File(cwd, "forge-nexus")
            // Walk up looking for forge-nexus sibling
            else -> cwd.walkUpFind { File(it, "forge-nexus").isDirectory }?.let { File(it, "forge-nexus") }
        }

        return if (nexusDir != null) {
            File(nexusDir, "recordings")
        } else {
            // Last resort — /tmp/ (ephemeral)
            File("/tmp/arena-recordings")
        }
    }
}
