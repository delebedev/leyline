package leyline.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.Toml
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Top-level configuration loaded from `leyline.toml`.
 *
 * Five sections:
 * - [server] — ports, timeouts, paths (infra)
 * - [game]   — seed, die roll, mulligan, timer (match setup)
 * - [ai]     — animation speed
 * - [draft]  — booster draft picker settings
 * - [dev]    — strict checking knobs for development
 *
 * Loaded once at startup; immutable after that.
 * CLI args and env vars override TOML values where noted.
 */
@Serializable
data class MatchConfig(
    val server: ServerConfig = ServerConfig(),
    val game: GameConfig = GameConfig(),
    val ai: AiConfig = AiConfig(),
    val draft: DraftConfig = DraftConfig(),
    val dev: DevConfig = DevConfig(),
) {
    companion object {
        private val log = LoggerFactory.getLogger(MatchConfig::class.java)

        private val toml =
            Toml {
                ignoreUnknownKeys = true
            }

        /** Default config file name. */
        const val DEFAULT_FILENAME = "leyline.toml"

        /**
         * Load config from [file]. Returns default config if file doesn't exist.
         * Throws on malformed TOML or invalid values.
         */
        fun load(file: File): MatchConfig {
            if (!file.exists()) {
                log.info("No config at {}, using defaults", file.absolutePath)
                return MatchConfig()
            }

            log.info("Loading config from {}", file.absolutePath)
            val text = file.readText()
            val config = toml.decodeFromString(serializer(), text)
            config.validate()
            log.info("Config loaded: {}", config.summary())
            return config
        }
    }

    /** Validate config values. Throws [IllegalArgumentException] on invalid state. */
    fun validate() {
        game.dieRollWinner?.let {
            require(it in 1..2) { "game.die_roll_winner must be 1 or 2, got $it" }
        }
        require(ai.speed >= 0.0) {
            "ai.speed must be non-negative, got ${ai.speed}"
        }
        game.seed?.let {
            require(it >= 0) { "game.seed must be non-negative, got $it" }
        }
        server.bridgeTimeoutMs?.let {
            require(it > 0) {
                "server.bridge_timeout_ms must be positive when set, got $it"
            }
        }
        require(draft.picker in setOf("forge", "model")) {
            "draft.picker must be 'forge' or 'model', got ${draft.picker}"
        }
        server.promptFailsafeMs?.let {
            require(it > 0) {
                "server.prompt_failsafe_ms must be positive when set, got $it"
            }
        }
        require(server.mulliganWaitMs > 0) {
            "server.mulligan_wait_ms must be positive, got ${server.mulliganWaitMs}"
        }
    }

    /**
     * AI delay multiplier derived from [AiConfig.speed].
     * speed=2 means 2x faster → delays halved (multiplier=0.5).
     * speed=0 means instant (multiplier=0).
     */
    val aiDelayMultiplier: Double get() = if (ai.speed == 0.0) 0.0 else 1.0 / ai.speed

    /**
     * Session pacing delay derived from [AiConfig.speed], applied between
     * auto-pass and combat steps so clients can animate. speed=0 disables
     * pacing entirely.
     */
    val paceDelayMs: Long get() = (200L * aiDelayMultiplier).toLong()

    /** One-line summary for startup log. */
    fun summary(): String =
        buildString {
            append("seed=")
            append(game.seed ?: "random")
            append(" first=")
            append(game.dieRollWinner?.let { "seat$it" } ?: "random")
            append(" skipMulligan=${game.skipMulligan}")
            append(" aiSpeed=${ai.speed}x")
            append(" bridgeTimeout=${server.bridgeTimeoutMs?.let { "${it}ms" } ?: "none"}")
            append(" draftPicker=${draft.picker}")
            append(" promptFailsafe=${server.promptFailsafeMs?.let { "${it}ms" } ?: "none"}")
            append(" mulliganWait=${server.mulliganWaitMs}ms")
            if (dev.strict || dev.strictPass) {
                append(" dev.strict=${dev.strict} dev.strict_pass=${dev.strictPass}")
            }
        }
}

/**
 * Server infrastructure config — ports, timeouts, paths.
 * CLI args override these values where applicable.
 */
@Serializable
data class ServerConfig(
    /** Front Door port (client auth + deck management). CLI: --fd-port */
    @SerialName("fd_port")
    val fdPort: Int = 30010,
    /** Match Door port (game protocol). CLI: --md-port */
    @SerialName("md_port")
    val mdPort: Int = 30003,
    /** Debug-control HTTP port. CLI: --debug-port */
    @SerialName("debug_port")
    val debugPort: Int = 8090,
    /** AccountServer (auth) HTTPS port. CLI: --account-port */
    @SerialName("account_port")
    val accountPort: Int = 9443,
    /** Management HTTP port (health checks, always starts). */
    @SerialName("management_port")
    val managementPort: Int = 8091,
    /** Priority/action timeout. Null disables human action-window timeout; prompt fail-safes stay finite. */
    @SerialName("bridge_timeout_ms")
    val bridgeTimeoutMs: Long? = null,
    /** Client-visible prompt fail-safe. Null disables prompt timeout for fully manual local play. */
    @SerialName("prompt_failsafe_ms")
    val promptFailsafeMs: Long? = 45_000L,
    /**
     * How long `advanceOrWait` waits for the AI's turn to return priority
     * before giving up and suppressing [ActionsAvailableReq]. Large in production
     * (the client may pace AI animations); tests override to fail fast.
     */
    @SerialName("ai_turn_wait_ms")
    val aiTurnWaitMs: Long = 30_000L,
    /** How long the engine waits for a mulligan decision from the client. */
    @SerialName("mulligan_wait_ms")
    val mulliganWaitMs: Long = 45_000L,
    /** Player database path (absolute, or relative to CWD). */
    @SerialName("player_db")
    val playerDb: String = "",
)

@Serializable
data class GameConfig(
    /**
     * RNG seed for deterministic shuffles. Null = random each game.
     * Useful for reproducing specific board states.
     */
    val seed: Long? = null,
    /**
     * Which seat wins the die roll (and goes first).
     * 1 = human goes first, 2 = AI goes first.
     * Null = randomize at match start (default).
     */
    @SerialName("die_roll_winner")
    val dieRollWinner: Int? = null,
    /**
     * Skip the mulligan phase — auto-keep opening hand.
     * Speeds up playtesting by going straight to Main1.
     */
    @SerialName("skip_mulligan")
    val skipMulligan: Boolean = false,
    /**
     * Send TimerStateMessage (rope/countdown) on priority grant.
     * Disable to suppress the decision timer in the client UI.
     */
    val timer: Boolean = true,
    /**
     * AI opponent deck name (looked up in player.db by name). "random" chooses a random deck.
     * Null = mirror match (AI uses the same deck as seat 1).
     */
    @SerialName("ai_deck")
    val aiDeck: String? = null,
    /** Run both game seats under Forge AI and attach the client as a read-only viewer. */
    @SerialName("spectator_mode")
    val spectatorMode: Boolean = false,
)

@Serializable
data class AiConfig(
    /**
     * AI animation speed factor.
     * 2.0 = twice as fast, 1.0 = default pacing, 0.5 = half speed.
     * 0 = instant (no delays).
     */
    val speed: Double = 1.0,
)

@Serializable
data class DraftConfig(
    /** Pick strategy for computer seats during booster drafts. */
    val picker: String = "forge",
    /** Directory containing one subdirectory per set with weights.json(.gz) and card_meta.json. */
    @SerialName("model_dir")
    val modelDir: String = "data/draft-models",
)

/**
 * Development-time checking knobs.
 *
 * - [strict] — crash on unexpected nulls/fallbacks (missing grpId, instanceId
 *   not in map, protocol sequencing errors). Off = warn + fallback (production behavior).
 * - [strictPass] — crash when auto-pass fires from missing data (bridge timeouts,
 *   prompt timeouts, auto-resolve). Off = pass priority silently (production behavior).
 */
@Serializable
data class DevConfig(
    val strict: Boolean = false,
    @SerialName("strict_pass")
    val strictPass: Boolean = false,
)
