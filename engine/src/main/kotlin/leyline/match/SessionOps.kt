package leyline.match

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

    fun sendPriorityState(bridge: GameBridge) = sendRealGameState(bridge)

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

internal enum class SynchronizationDrain {
    None,
    Completed,
    Stale,
}

internal data class DrainOutcome(
    val sent: Boolean,
    val synchronization: SynchronizationDrain = SynchronizationDrain.None,
    val synchronizationActionId: String? = null,
) {
    val progressed: Boolean get() = sent || synchronization == SynchronizationDrain.Completed
}

/** Deliver committed batches, then release at most the synchronization stop observed at entry. */
internal fun drainCoordinatorBarrier(
    sink: GreMessageSink,
    bridge: GameBridge,
    seatId: SeatId,
    betweenBatches: () -> Unit = {},
    beforeDrain: () -> Unit = {},
): DrainOutcome =
    drainOneCoordinatorBarrier(
        sink = sink,
        synchronizationActionId =
            bridge
                .actionBridge(seatId)
                .getPending()
                ?.takeIf { it.state.kind == leyline.bridge.handoff.PendingActionKind.SYNC_ONLY }
                ?.actionId,
        drainCommitted = { bridge.cutCoordinator.drain(seatId) },
        completeSynchronization = { actionId -> bridge.actionBridge(seatId).completeSyncPass(actionId) },
        awaitNext = bridge::awaitPriority,
        failDelivery = bridge.cutCoordinator::failDelivery,
        betweenBatches = betweenBatches,
        beforeDrain = beforeDrain,
    )

@org.jetbrains.annotations.VisibleForTesting
internal fun drainOneCoordinatorBarrier(
    sink: GreMessageSink,
    synchronizationActionId: String?,
    drainCommitted: () -> List<List<GREToClientMessage>>,
    completeSynchronization: (String) -> Boolean,
    awaitNext: () -> Unit,
    failDelivery: (Exception) -> Nothing,
    betweenBatches: () -> Unit = {},
    beforeDrain: () -> Unit = {},
): DrainOutcome {
    var sent = false

    fun deliverCommittedBatches() {
        beforeDrain()
        val batches = drainCommitted()
        try {
            batches.forEach { batch ->
                if (sent) betweenBatches()
                sink.sendBundledGRE(batch)
                sent = true
            }
        } catch (ex: Exception) {
            failDelivery(ex)
        }
    }
    deliverCommittedBatches()
    if (synchronizationActionId == null) return DrainOutcome(sent)
    if (!completeSynchronization(synchronizationActionId)) {
        return DrainOutcome(sent, SynchronizationDrain.Stale, synchronizationActionId)
    }
    awaitNext()
    // Publish the next engine-owned horizon without releasing it. A following
    // invocation may release that exact synchronization stop; Visible windows
    // remain blocked for a correlated client answer.
    deliverCommittedBatches()
    return DrainOutcome(sent, SynchronizationDrain.Completed, synchronizationActionId)
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
 * [HandlerConstructorContractTest] pins each concrete handler's
 * primary-constructor parameter types to this narrow-interface contract.
 */
interface SessionOps :
    GreMessageSink,
    SessionCounters,
    Pacing,
    ActionReceiver {
    val recorder: MatchRecorder? get() = null
    val matchId: String

    /** Build a single GRE message with an explicit msgId (no side-effect on counters). */
    override fun makeGRE(
        type: GREMessageType,
        gsId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage {
        val gre =
            GREToClientMessage
                .newBuilder()
                .setType(type)
                .setMsgId(msgId)
                .setGameStateId(gsId)
                .addSystemSeatIds(seatId.value)
        configure(gre)
        return gre.build()
    }
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
