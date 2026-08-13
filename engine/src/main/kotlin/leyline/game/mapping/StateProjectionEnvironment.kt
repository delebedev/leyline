package leyline.game.mapping

import leyline.game.data.CardProtoBuilder
import leyline.game.state.GameBridge

/** Stable reference data and match configuration used by state projection. */
data class StateProjectionEnvironment(
    val cardProto: CardProtoBuilder,
    val matchConfig: MatchProjectionConfig,
    val persistentFeedReferences: PersistentFeedReferences,
)

/** Match-scoped protocol configuration frozen before state projection begins. */
data class MatchProjectionConfig(
    val isBrawlOrCommander: Boolean,
)

/** Shell adapter that materializes the read-only state-projection environment. */
object StateProjectionEnvironmentCapture {
    fun from(bridge: GameBridge): StateProjectionEnvironment =
        StateProjectionEnvironment(
            cardProto = bridge.cardProto,
            matchConfig = MatchProjectionConfig(bridge.isBrawlOrCommander),
            persistentFeedReferences = PersistentFeedReferences(bridge.cardRepository),
        )
}
