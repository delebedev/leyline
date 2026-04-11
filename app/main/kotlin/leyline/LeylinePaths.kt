package leyline

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Filesystem paths for local session artifacts.
 *
 * Session-scoped artifacts land under `/tmp/leyline/sessions/<session>/`.
 * Engine dumps land under `/tmp/leyline/engine/`.
 * Persistent data lives under `~/Library/Application Support/dev.leyline/`.
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

    val SESSION_ROOT: File = File(TMP_ROOT, "sessions")
    val SESSION_DIR: File get() = File(SESSION_ROOT, sessionTag)
    val ENGINE_DUMP: File = File(TMP_ROOT, "engine")

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
