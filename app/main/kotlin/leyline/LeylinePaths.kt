package leyline

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Filesystem paths for local session artifacts.
 *
 * Session-scoped artifacts land under `$SESSION_ROOT/<session>/`.
 * Engine dumps land under `$ENGINE_DUMP/`.
 * Persistent data lives under `~/Library/Application Support/dev.leyline/`.
 *
 * `SESSION_ROOT` and `ENGINE_DUMP` default to `/tmp/leyline/{sessions,engine}`
 * and can be overridden. Sysprop wins over env var:
 * - sessions: `-Dleyline.sessions.root=…` or `LEYLINE_SESSIONS_ROOT=…`
 * - engine:   `-Dleyline.engine.dump=…`   or `LEYLINE_ENGINE_DUMP=…`
 */
object LeylinePaths {
    private val TMP_ROOT = File("/tmp/leyline")

    /** Persistent app data dir — TLS certs, player DB, etc. */
    val APP_DATA: File = File(System.getProperty("user.home"), "Library/Application Support/dev.leyline")

    /** Default player database path (outside repo, survives worktree switches). */
    val PLAYER_DB: File = File(APP_DATA, "player.db")

    @Volatile
    var sessionTag: String = newSessionTag()
        private set

    val SESSION_ROOT: File
        get() = resolveOverride("leyline.sessions.root", "LEYLINE_SESSIONS_ROOT")
            ?: File(TMP_ROOT, "sessions")

    val SESSION_DIR: File get() = File(SESSION_ROOT, sessionTag)

    val ENGINE_DUMP: File
        get() = resolveOverride("leyline.engine.dump", "LEYLINE_ENGINE_DUMP")
            ?: File(TMP_ROOT, "engine")

    private fun resolveOverride(sysprop: String, env: String): File? =
        (System.getProperty(sysprop) ?: System.getenv(env))
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)

    private fun newSessionTag(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))

    fun rotateSession() {
        sessionTag = newSessionTag()
        ensureDirectories()
    }

    fun ensureDirectories() {
        SESSION_DIR.mkdirs()
        ENGINE_DUMP.mkdirs()
    }

    init {
        ensureDirectories()
    }
}
