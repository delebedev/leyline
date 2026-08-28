package leyline.config

import java.io.File

/**
 * Derived resource locations for the running process.
 *
 * Relative values resolve against the configuration base directory, never the
 * process working directory. A named instance
 * derives isolated state and artifact paths beneath the configured bases, so
 * additional servers from one worktree never share mutable state or artifacts.
 */
class ResolvedPaths(
    /** Resolved read-only content root. */
    val contentRoot: File,
    /** Resolved persistent mutable state directory. */
    val stateDir: File,
    /** Resolved per-instance artifact root. */
    val artifactsRoot: File,
) {
    /** Persistent player database file. */
    val playerDb: File get() = File(stateDir, "player.db")

    /** Protocol dump output directory (outbound GRE messages). */
    val engineDump: File get() = File(artifactsRoot, "engine")

    /** Session journal file. */
    val sessionJournal: File get() = File(artifactsRoot, "sessions.jsonl")

    /** Shared puzzle library root (`.pzl` fixtures and acceptance suites). */
    val puzzlesDir: File get() = File(contentRoot, "data/puzzles")

    /** Resolved booster-draft model directory (relative values anchor to the content root). */
    fun draftModelDir(modelDir: String): File = contentRoot.resolve(modelDir)

    fun ensureDirectories() {
        stateDir.mkdirs()
        engineDump.mkdirs()
    }

    companion object {
        fun resolve(
            baseDir: File,
            paths: PathSettings,
            instance: String?,
            defaultStateDir: File,
        ): ResolvedPaths {
            val contentRoot = if (paths.content.isBlank()) baseDir else baseDir.resolve(paths.content)
            val baseState = if (paths.state.isBlank()) defaultStateDir else baseDir.resolve(paths.state)
            val baseArtifacts = baseDir.resolve(paths.artifacts)
            val stateDir = instance?.let { File(baseState, it) } ?: baseState
            val artifactsRoot = instance?.let { File(baseArtifacts, it) } ?: baseArtifacts
            return ResolvedPaths(contentRoot = contentRoot, stateDir = stateDir, artifactsRoot = artifactsRoot)
        }
    }
}
