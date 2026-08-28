package leyline.config

import kotlinx.serialization.Serializable

/**
 * Immutable application configuration snapshot, resolved once per process
 * lifetime by [LeylineConfigResolver].
 *
 * The root graph composes owner-shaped settings directly. An optional
 * `leyline.toml` at the installation/worktree root is the single
 * configuration file. Effective values follow typed default < TOML <
 * `LEYLINE_*` environment override. Relative path values resolve against the
 * configuration base directory, never the process working directory.
 */
@Serializable
data class LeylineConfig(
    /** Native-client head: listeners, advertised endpoint, operator ports. */
    val native: NativeSettings = NativeSettings(),
    /** Browser-facing web head: listener, player, auth, email, rate limiting. */
    val web: WebSettings = WebSettings(),
    /** Engine behavior: match timing, match defaults, draft policy, diagnostics. */
    val engine: EngineSettings = EngineSettings(),
    /** Resource locations: content, mutable state, and per-instance artifacts. */
    val paths: PathSettings = PathSettings(),
) {
    companion object {
        /** Fixed configuration file name at the installation/worktree root. */
        const val FILENAME = "leyline.toml"
    }

    /** Validate the complete snapshot. Throws [IllegalArgumentException] on invalid combinations. */
    fun validate() {
        native.validate()
        web.validate()
        engine.validate()
        paths.validate()
    }
}
