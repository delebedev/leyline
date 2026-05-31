package leyline.match

import forge.game.Game
import leyline.bridge.types.SeatId
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.MessageCounter
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Outbound GRE wire surface — emitting messages, bundles, and game-over.
 *
 * Handlers that only need to push messages (not receive inbound actions,
 * not trace, not pace) depend on this interface alone.
 */
interface GreMessageSink {
    fun sendBundledGRE(messages: List<GREToClientMessage>)

    fun sendRealGameState(
        bridge: GameBridge,
        revealForSeat: Int? = null,
    )

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
 * `counter` is a `var` so sessions can adopt a peer's counter when paired —
 * e.g. the Familiar seat shares the human seat's [MessageCounter] via the
 * bridge.
 */
interface SessionCounters {
    val seatId: SeatId
    var counter: MessageCounter
}

/** Optional tracing for conformance/replay telemetry. */
interface SessionTracer {
    val recorder: MatchRecorder? get() = null

    fun traceEvent(
        type: MatchEventType,
        game: Game,
        detail: String,
    )
}

/**
 * Accessor for the per-session [BundleBuilder].
 *
 * Implemented by sessions that drive game logic ([MatchSession]). Read-only
 * sessions that never build bundles ([FamiliarSession]) do not implement this
 * interface — the type system enforces the absence rather than a runtime null.
 */
interface BundleBuilderHolder {
    val bundleBuilder: BundleBuilder
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

    fun onOrderResp(greMsg: ClientToGREMessage) {}

    fun onEffectCost(greMsg: ClientToGREMessage) {}

    fun onGroupResp(greMsg: ClientToGREMessage) {}

    fun onCancelAction(greMsg: ClientToGREMessage) {}

    fun onCastingTimeOptions(greMsg: ClientToGREMessage) {}

    fun onSearch(greMsg: ClientToGREMessage) {}

    fun onAssignDamage(greMsg: ClientToGREMessage) {}

    fun onOptionalActionResp(greMsg: ClientToGREMessage) {}

    fun onNumericInputResp(greMsg: ClientToGREMessage) {}

    fun onConcede() {}

    fun onSettings(greMsg: ClientToGREMessage) {}

    fun onMulliganKeep() {}

    fun onPuzzleStart() {}
}

/**
 * Connection-bound session contract — storage type for [MatchRegistry]
 * and [MatchHandler], and the declared supertype of both
 * [MatchSession] and [FamiliarSession].
 *
 * Extends five focused interfaces so **handlers should take the
 * sub-interfaces they need**, not `SessionOps` as a whole. The only
 * code that should still accept `SessionOps` is:
 *
 * - `MatchRegistry` (stores sessions as a single value type)
 * - `MatchHandler` (dispatches all inbound message types, needs
 *   the full [ActionReceiver] surface)
 * - Whole-surface test doubles (e.g. `SessionTraceOps`).
 *
 * [BundleBuilderHolder] and a non-null `gameBridge` are NOT part of this
 * contract — sessions that drive game logic ([MatchSession]) implement
 * those separately via [GameOps]. Read-only sessions ([FamiliarSession])
 * do not, and the type system enforces the asymmetry.
 *
 * [HandlerConstructorContractTest] pins each narrow handler contract
 * at compile time.
 */
interface SessionOps :
    GreMessageSink,
    SessionCounters,
    SessionTracer,
    Pacing,
    ActionReceiver {
    val matchId: String
}

/**
 * Game-bound session contract — adds the non-null game state surface
 * to [SessionOps]. Implemented by [MatchSession]; not implemented by
 * [FamiliarSession].
 */
interface GameOps :
    SessionOps,
    BundleBuilderHolder {
    val gameBridge: GameBridge
}
