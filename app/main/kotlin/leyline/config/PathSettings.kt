package leyline.config

import kotlinx.serialization.Serializable

/**
 * Resource locations for the running process.
 *
 * Values resolve relative to the configuration file, never the process
 * working directory. The ordinary (non-instance) process uses the durable
 * user-level state location shared across worktrees; a named instance
 * (`LEYLINE_INSTANCE`, read by [LeylineConfigResolver]) derives isolated
 * state and artifact paths beneath the configured bases.
 */
@Serializable
data class PathSettings(
    /**
     * Read-only bundled content root (decks, draft models, puzzles).
     * Empty = the configuration file's directory.
     */
    val content: String = "",
    /**
     * Persistent mutable state directory; `player.db` lives inside.
     * Empty = durable user-level location shared across worktrees.
     */
    val state: String = "",
    /** Per-instance artifact root for logs and sessions. */
    val artifacts: String = "logs",
) {
    fun validate() {
        require(artifacts.isNotBlank()) { "paths.artifacts must not be blank" }
    }
}
