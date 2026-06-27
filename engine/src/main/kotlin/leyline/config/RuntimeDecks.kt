package leyline.config

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class RuntimeMatchConfig(
    val matchId: String,
    val seat1Deck: String? = null,
    val seat2Deck: String? = null,
    val puzzle: String? = null,
    val spectatorMode: Boolean? = null,
)

@Serializable
data class RuntimeMatchLaunchResponse(
    val matchId: String,
    val wireMatchId: String,
    val accepted: Boolean,
    val config: RuntimeMatchConfig,
)

class RuntimeMatchConfigRegistry {
    private val configs = ConcurrentHashMap<String, RuntimeMatchConfig>()

    fun configure(config: RuntimeMatchConfig): RuntimeMatchLaunchResponse {
        val stored = put(config)
        return RuntimeMatchLaunchResponse(
            matchId = stored.matchId,
            wireMatchId = stored.matchId,
            accepted = true,
            config = stored,
        )
    }

    fun put(config: RuntimeMatchConfig): RuntimeMatchConfig {
        val matchId = config.matchId.trim()
        require(matchId.isNotEmpty()) { "matchId is required" }
        val normalized =
            config.copy(
                matchId = matchId,
                seat1Deck = config.seat1Deck?.trim()?.takeIf { it.isNotEmpty() },
                seat2Deck = config.seat2Deck?.trim()?.takeIf { it.isNotEmpty() },
                puzzle = config.puzzle?.trim()?.takeIf { it.isNotEmpty() },
            )
        configs[matchId] = normalized
        return normalized
    }

    fun get(matchId: String): RuntimeMatchConfig? = configs[matchId]

    fun remove(matchId: String): RuntimeMatchConfig? = configs.remove(matchId)

    fun clear() = configs.clear()
}
