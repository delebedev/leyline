package leyline.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.Toml
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Code-first playtest configuration loaded from TOML.
 *
 * Covers game setup (seed, die roll), deck selection, and AI pacing.
 * Loaded once at startup; immutable after that.
 */
@Serializable
data class PlaytestConfig(
    val game: GameConfig = GameConfig(),
    val decks: DeckConfig = DeckConfig(),
    val ai: AiConfig = AiConfig(),
) {
    companion object {
        private val log = LoggerFactory.getLogger(PlaytestConfig::class.java)

        private val toml = Toml {
            ignoreUnknownKeys = true
        }

        /** Default config file path relative to project root. */
        const val DEFAULT_FILENAME = "playtest.toml"

        /**
         * Load config from [file]. Returns default config if file doesn't exist.
         * Throws on malformed TOML or invalid values.
         */
        fun load(file: File): PlaytestConfig {
            if (!file.exists()) {
                log.info("No config at {}, using defaults", file.absolutePath)
                return PlaytestConfig()
            }

            log.info("Loading playtest config from {}", file.absolutePath)
            val text = file.readText()
            val config = toml.decodeFromString(serializer(), text)
            config.validate()
            log.info("Config loaded: {}", config.summary())
            return config
        }

        /** Resolve deck file path relative to a base directory. */
        fun resolveDeckFile(deckName: String, baseDir: File): File {
            // If it already looks like a path, use as-is
            if (deckName.contains('/') || deckName.contains('\\')) {
                return File(deckName).let { if (it.isAbsolute) it else File(baseDir, deckName) }
            }
            // Otherwise look in decks/ subdir, with or without .txt
            val decksDir = File(baseDir, "decks")
            val withExt = if (deckName.endsWith(".txt")) deckName else "$deckName.txt"
            return File(decksDir, withExt)
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
        append(" decks=[${decks.seat1}, ${decks.seat2}]")
        append(" aiSpeed=${ai.speed}x")
    }
}

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
)

@Serializable
data class DeckConfig(
    /**
     * Deck file for seat 1 (human player). Name resolved in decks/ dir.
     * Supports standard Arena/MTGO export format.
     */
    val seat1: String = "green-stompy",

    /**
     * Deck file for seat 2 (AI / Sparky). Name resolved in decks/ dir.
     */
    val seat2: String = "green-stompy",
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
