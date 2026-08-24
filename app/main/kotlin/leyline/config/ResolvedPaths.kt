package leyline.config

import java.io.File

/**
 * Derived resource locations for the running process.
 *
 * Relative values resolve against the configuration base directory (the
 * TOML's parent), never the process working directory. A named instance
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

    /** Session journal directory. */
    val sessionsRoot: File get() = File(artifactsRoot, "sessions")

    /** Puzzle library root (`.pzl` fixtures). */
    val puzzlesDir: File get() = File(contentRoot, "puzzles")

    /** Resolved booster-draft model directory (relative values anchor to the content root). */
    fun draftModelDir(modelDir: String): File = resolveAgainst(contentRoot, modelDir)

    fun ensureDirectories() {
        stateDir.mkdirs()
        engineDump.mkdirs()
        sessionsRoot.mkdirs()
    }

    companion object {
        fun resolve(
            baseDir: File,
            paths: PathSettings,
            instance: String?,
            defaultStateDir: File,
        ): ResolvedPaths {
            val contentRoot = if (paths.content.isBlank()) baseDir else resolveAgainst(baseDir, paths.content)
            val baseState = if (paths.state.isBlank()) defaultStateDir else resolveAgainst(baseDir, paths.state)
            val baseArtifacts = resolveAgainst(baseDir, paths.artifacts)
            val stateDir = instance?.let { File(baseState, it) } ?: baseState
            val artifactsRoot = instance?.let { File(baseArtifacts, it) } ?: baseArtifacts
            return ResolvedPaths(contentRoot = contentRoot, stateDir = stateDir, artifactsRoot = artifactsRoot)
        }

        private fun resolveAgainst(
            baseDir: File,
            path: String,
        ): File {
            val file = File(path)
            return if (file.isAbsolute) file else File(baseDir, path)
        }
    }
}
