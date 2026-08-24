package leyline.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Process-head settings for the browser-facing web head: listener, player
 * identity, authentication, email, rate limiting, and pacing.
 *
 * Secrets ([authSecret], [resendApiKey]) are supplied externally via
 * `LEYLINE_*` environment overrides and are redacted from startup reporting.
 * The active head is decided by the entry point; there are no head enable
 * flags.
 */
@Serializable
data class WebSettings(
    /** Web head listener port. */
    val port: Int = 8080,
    /** Web head bind address (loopback by default). */
    val host: String = "127.0.0.1",
    /** Default local player identity used for web matches. */
    @SerialName("player_id")
    val playerId: String = "web-player",
    /** Web authentication signing secret. Required when the web head is active. */
    @SerialName("auth_secret")
    val authSecret: String = "",
    /** Optional fixed six-digit login code. Requires [allowFixedLoginCode]. */
    @SerialName("login_code")
    val loginCode: String = "",
    /** Explicit opt-in for the fixed login code. */
    @SerialName("allow_fixed_login_code")
    val allowFixedLoginCode: Boolean = false,
    /** Resend API key for transactional email. Empty = in-memory dev sender. */
    @SerialName("resend_api_key")
    val resendApiKey: String = "",
    /** From address for transactional email. */
    @SerialName("resend_from")
    val resendFrom: String = "login@localhost",
    /** Auth rate limiting on/off. */
    @SerialName("rate_limit_enabled")
    val rateLimitEnabled: Boolean = true,
    /** Max login attempts per window. */
    @SerialName("rate_limit")
    val rateLimit: Int = 10,
    /** Rate-limit window (ms). */
    @SerialName("rate_limit_window_ms")
    val rateLimitWindowMs: Long = 60_000,
    /**
     * Engine pacing for web matches. Browser clients animate on their own, so
     * the web profile runs the engine at full speed by default.
     */
    @SerialName("ai_speed")
    val aiSpeed: Double = 0.0,
) {
    fun validate() {
        require(port in 1..65535) { "web.port must be in 1..65535, got $port" }
        require(host.isNotBlank()) { "web.host must not be blank" }
        require(playerId.isNotBlank()) { "web.player_id must not be blank" }
        require(rateLimit > 0) { "web.rate_limit must be positive, got $rateLimit" }
        require(rateLimitWindowMs > 0) { "web.rate_limit_window_ms must be positive, got $rateLimitWindowMs" }
        require(aiSpeed >= 0.0) { "web.ai_speed must be non-negative, got $aiSpeed" }
    }

    companion object {
        /** Canonical keys whose values are secrets and must be redacted from startup reporting. */
        val SECRET_PATHS: Set<String> = setOf("web.auth_secret", "web.resend_api_key")
    }
}
