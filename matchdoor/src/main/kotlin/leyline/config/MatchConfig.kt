package leyline.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.Toml
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Top-level configuration loaded from `leyline.toml`.
 *
 * Three sections:
 * - [server] — ports, timeouts, paths (infra)
 * - [game]   — seed, die roll, mulligan, timer (match setup)
 * - [ai]     — animation speed
 *
 * Loaded once at startup; immutable after that.
 * CLI args and env vars override TOML values where noted.
 */
@Serializable
data class MatchConfig(
    val server: ServerConfig = ServerConfig(),
    val game: GameConfig = GameConfig(),
    val ai: AiConfig = AiConfig(),
) {
    companion object {
        private val log = LoggerFactory.getLogger(MatchConfig::class.java)

        private val toml = Toml {
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
        require(game.dieRollWinner in 1..2) {
            "game.die_roll_winner must be 1 or 2, got ${game.dieRollWinner}"
        }
        require(ai.speed >= 0.0) {
            "ai.speed must be non-negative, got ${ai.speed}"
        }
        game.seed?.let {
            require(it >= 0) { "game.seed must be non-negative, got $it" }
        }
        require(server.bridgeTimeoutMs > 0) {
            "server.bridge_timeout_ms must be positive, got ${server.bridgeTimeoutMs}"
        }
    }

    /**
     * AI delay multiplier derived from [AiConfig.speed].
     * speed=2 means 2x faster → delays halved (multiplier=0.5).
     * speed=0 means instant (multiplier=0).
     */
    val aiDelayMultiplier: Double get() = if (ai.speed == 0.0) 0.0 else 1.0 / ai.speed

    /** One-line summary for startup log. */
    fun summary(): String = buildString {
        append("seed=")
        append(game.seed ?: "random")
        append(" first=seat${game.dieRollWinner}")
        append(" skipMulligan=${game.skipMulligan}")
        append(" aiSpeed=${ai.speed}x")
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

    /** Debug panel HTTP port. CLI: --debug-port */
    @SerialName("debug_port")
    val debugPort: Int = 8090,

    /** AccountServer (auth) HTTPS port. CLI: --account-port */
    @SerialName("account_port")
    val accountPort: Int = 9443,

    /** Management HTTP port (health checks, always starts). */
    @SerialName("management_port")
    val managementPort: Int = 8091,

    /** Bridge timeout — how long the engine waits for client responses (ms). */
    @SerialName("bridge_timeout_ms")
    val bridgeTimeoutMs: Long = 120_000L,

    /** Player database path (relative to CWD or absolute). */
    @SerialName("player_db")
    val playerDb: String = "data/player.db",
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
     * 1 = human goes first, 2 = AI goes first (default).
     */
    @SerialName("die_roll_winner")
    val dieRollWinner: Int = 2,

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
     * AI opponent deck name (looked up in player.db by name).
     * Null = mirror match (AI uses the same deck as seat 1).
     */
    @SerialName("ai_deck")
    val aiDeck: String? = null,
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
