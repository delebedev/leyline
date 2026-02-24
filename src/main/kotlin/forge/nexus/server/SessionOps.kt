package forge.nexus.server

import forge.game.Game
import forge.nexus.debug.GameStateCollector
import forge.nexus.game.BundleBuilder
import forge.nexus.game.GameBridge
import forge.nexus.game.MessageCounter
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Shared infrastructure contract for sub-handlers extracted from [MatchSession].
 *
 * Provides counter access, message sending, and tracing without exposing
 * MatchSession internals. Handlers ([CombatHandler], [TargetingHandler],
 * [AutoPassEngine]) take this interface rather than the full session.
 */
interface SessionOps {
    val seatId: Int
    val matchId: String
    val counter: MessageCounter

    fun sendBundledGRE(messages: List<GREToClientMessage>)
    fun sendRealGameState(bridge: GameBridge)
    fun sendBundle(result: BundleBuilder.BundleResult)
    fun sendGameOver()
    fun traceEvent(type: GameStateCollector.EventType, game: Game, detail: String)
    fun paceDelay(multiplier: Int)

    /** Build a single GRE message with explicit IDs. */
    fun makeGRE(
        type: GREMessageType,
        gsId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage
}
