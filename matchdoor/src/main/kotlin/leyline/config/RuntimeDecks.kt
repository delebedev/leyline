package leyline.config

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class RuntimeDecks(
    val seat1Deck: String? = null,
    val seat2Deck: String? = null,
)

@Serializable
data class RuntimeMatchConfig(
    val matchId: String,
    val seat1Deck: String? = null,
    val seat2Deck: String? = null,
    val puzzle: String? = null,
    val spectatorMode: Boolean? = null,
)

class RuntimeMatchConfigRegistry {
    private val configs = ConcurrentHashMap<String, RuntimeMatchConfig>()

    fun put(config: RuntimeMatchConfig): RuntimeMatchConfig {
        val matchId = config.matchId.trim()
        require(matchId.isNotEmpty()) { "matchId is required" }
        val normalized = config.copy(matchId = matchId)
        configs[matchId] = normalized
        return normalized
    }

    fun get(matchId: String): RuntimeMatchConfig? = configs[matchId]

    fun remove(matchId: String): RuntimeMatchConfig? = configs.remove(matchId)

    fun clear() = configs.clear()
}
