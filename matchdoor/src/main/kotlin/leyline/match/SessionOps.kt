package leyline.match

import forge.game.Game
import leyline.game.BundleBuilder
import leyline.game.GameBridge
import leyline.game.MessageCounter
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Shared infrastructure contract for sub-handlers extracted from [MatchSession].
 *
 * Provides counter access, message sending, and tracing without exposing
 * MatchSession internals. Handlers ([CombatHandler], [TargetingHandler],
 * [AutoPassEngine]) take this interface rather than the full session.
 *
 * Two implementations: [MatchSession] (human, full game logic) and
 * [FamiliarSession] (read-only mirror, no-op actions). Code that needs
 * session access should use this interface — never downcast to a concrete type.
 */
interface SessionOps {
    val seatId: Int
    val matchId: String
    var counter: MessageCounter

    /** Game bridge — non-null for [MatchSession], null for [FamiliarSession]. */
    val gameBridge: GameBridge? get() = null

    /** Session recorder — non-null when recording is enabled. */
    val recorder: MatchRecorder? get() = null

    /** Wire the game bridge. Asserts counter identity for [MatchSession]. No-op for read-only sessions. */
    fun connectBridge(bridge: GameBridge) {}

    fun sendBundledGRE(messages: List<GREToClientMessage>)
    fun sendRealGameState(bridge: GameBridge)
    fun sendBundle(result: BundleBuilder.BundleResult)
    fun sendGameOver(reason: ResultReason = ResultReason.Game_ae0a)
    fun traceEvent(type: MatchEventType, game: Game, detail: String)
    fun paceDelay(multiplier: Int)

    /** Build a single GRE message with explicit IDs. */
    fun makeGRE(
        type: GREMessageType,
        gsId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage

    // -- Action handlers -- default no-ops for read-only sessions ----------

    /** Handle PerformActionResp. Default no-op for read-only sessions. */
    fun onPerformAction(greMsg: ClientToGREMessage) {}

    /** Handle DeclareAttackersResp. Default no-op for read-only sessions. */
    fun onDeclareAttackers(greMsg: ClientToGREMessage) {}

    /** Handle DeclareBlockersResp. Default no-op for read-only sessions. */
    fun onDeclareBlockers(greMsg: ClientToGREMessage) {}

    /** Handle SelectTargetsResp. Default no-op for read-only sessions. */
    fun onSelectTargets(greMsg: ClientToGREMessage) {}

    /** Handle SubmitTargetsReq. Default no-op for read-only sessions. */
    fun onSubmitTargets(greMsg: ClientToGREMessage) {}

    /** Handle SelectNResp. Default no-op for read-only sessions. */
    fun onSelectN(greMsg: ClientToGREMessage) {}

    /** Handle GroupResp. Default no-op for read-only sessions. */
    fun onGroupResp(greMsg: ClientToGREMessage) {}

    /** Handle CancelActionResp. Default no-op for read-only sessions. */
    fun onCancelAction(greMsg: ClientToGREMessage) {}

    /** Handle CastingTimeOptionsResp. Default no-op for read-only sessions. */
    fun onCastingTimeOptions(greMsg: ClientToGREMessage) {}

    /** Handle SearchResp. Default no-op for read-only sessions. */
    fun onSearch(greMsg: ClientToGREMessage) {}

    /** Handle concession. Default no-op for read-only sessions. */
    fun onConcede() {}

    /** Handle settings update. Default no-op for read-only sessions. */
    fun onSettings(greMsg: ClientToGREMessage) {}

    /** Handle mulligan keep decision. Default no-op for read-only sessions. */
    fun onMulliganKeep() {}

    /** Handle puzzle start. Default no-op for read-only sessions. */
    fun onPuzzleStart() {}
}
