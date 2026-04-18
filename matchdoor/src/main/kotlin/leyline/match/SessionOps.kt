package leyline.match

import forge.game.Game
import leyline.bridge.SeatId
import leyline.game.BundleBuilder
import leyline.game.GameBridge
import leyline.game.MessageCounter
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Outbound GRE wire surface — emitting messages, bundles, and game-over.
 *
 * Handlers that only need to push messages (not receive inbound actions,
 * not trace, not pace) depend on this interface alone.
 */
interface GreMessageSink {
    fun sendBundledGRE(messages: List<GREToClientMessage>)
    fun sendRealGameState(bridge: GameBridge, revealForSeat: Int? = null)
    fun sendBundle(result: BundleBuilder.BundleResult)
    fun sendGameOver(reason: ResultReason = ResultReason.Game_ae0a)

    /** Build a single GRE message with explicit IDs. */
    fun makeGRE(
        type: GREMessageType,
        gsId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage
}

/**
 * Session identity and the shared protocol counter.
 *
 * `counter` is a `var` because the bridge-connection race can force
 * adoption of the bridge's counter after session construction
 * (see `MatchSession.connectBridge`).
 */
interface SessionCounters {
    val seatId: SeatId
    var counter: MessageCounter
}

/** Optional tracing for conformance/replay telemetry. */
interface SessionTracer {
    val recorder: MatchRecorder? get() = null
    fun traceEvent(type: MatchEventType, game: Game, detail: String)
}

/**
 * Late-bound accessor for the per-session [BundleBuilder].
 *
 * Non-null only after [MatchSession.connectBridge]. Handlers that build
 * bundles read this via `bundles.bundleBuilder!!` in code paths that are
 * only reachable after bridge connection (same pattern as before).
 */
interface BundleBuilderHolder {
    val bundleBuilder: BundleBuilder? get() = null
}

/** Engine pacing (AI turn delay, etc.). */
interface Pacing {
    fun paceDelay(multiplier: Int)
}

/**
 * Inbound client-action dispatch surface.
 *
 * All methods default to no-op so read-only sessions (FamiliarSession)
 * inherit silent behavior for action messages they never drive.
 */
interface ActionReceiver {
    fun onPerformAction(greMsg: ClientToGREMessage) {}
    fun onDeclareAttackers(greMsg: ClientToGREMessage) {}
    fun onDeclareBlockers(greMsg: ClientToGREMessage) {}
    fun onSelectTargets(greMsg: ClientToGREMessage) {}
    fun onSubmitTargets(greMsg: ClientToGREMessage) {}
    fun onSelectN(greMsg: ClientToGREMessage) {}
    fun onGroupResp(greMsg: ClientToGREMessage) {}
    fun onCancelAction(greMsg: ClientToGREMessage) {}
    fun onCastingTimeOptions(greMsg: ClientToGREMessage) {}
    fun onSearch(greMsg: ClientToGREMessage) {}
    fun onAssignDamage(greMsg: ClientToGREMessage) {}
    fun onOptionalActionResp(greMsg: ClientToGREMessage) {}
    fun onConcede() {}
    fun onSettings(greMsg: ClientToGREMessage) {}
    fun onMulliganKeep() {}
    fun onPuzzleStart() {}
}

/**
 * Composite session contract — storage type for [MatchRegistry]
 * and [MatchHandler], and the declared supertype of both
 * [MatchSession] and [FamiliarSession].
 *
 * Extends six focused interfaces so **handlers should take the
 * sub-interfaces they need**, not `SessionOps` as a whole. Handler
 * narrowing to the pattern lands in follow-up commits; see
 * [CombatHandler], [TargetingHandler], [OptionalActionHandler],
 * [AutoPassEngine].
 *
 * The only code that should still accept `SessionOps` is:
 * - `MatchRegistry` (stores sessions as a single value type)
 * - `MatchHandler` (dispatches all inbound message types, needs
 *   the full [ActionReceiver] surface)
 * - Whole-surface test doubles (e.g. `SessionTraceOps`).
 */
interface SessionOps :
    GreMessageSink,
    SessionCounters,
    SessionTracer,
    BundleBuilderHolder,
    Pacing,
    ActionReceiver {

    val matchId: String

    /** Game bridge — non-null for [MatchSession], null for [FamiliarSession]. */
    val gameBridge: GameBridge? get() = null

    /** Wire the game bridge. Asserts counter identity for [MatchSession]. No-op for read-only sessions. */
    fun connectBridge(bridge: GameBridge) {}
}
