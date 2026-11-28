package leyline.match

import leyline.bridge.types.SeatId
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.*
import java.util.concurrent.TimeoutException

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

    fun sendGameOver()
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

/**
 * Deliver committed batches, then release each exact synchronization stop observed at the boundary.
 *
 * Two limits end a drain that keeps releasing synchronization stops. Once it has released
 * [drainStopLimit] stops, it fails after [drainTimeoutMs] in total. At any stop count it
 * fails after [drainCeilingMs]. Total time alone cannot separate unbounded automatic work
 * from a finite chain whose AI decisions are slow on the host, so a chain below the stop
 * limit may run past [drainTimeoutMs]. An endless automatic stream therefore holds the
 * session lock for at most [drainCeilingMs]. A drain whose engine publishes nothing more
 * returns without failure when one priority wait expires.
 */
internal fun drainCoordinatorBarrier(
    sink: GreMessageSink,
    bridge: GameBridge,
    seatId: SeatId,
    betweenBatches: () -> Unit = {},
    beforeDrain: () -> Unit = {},
    drainTimeoutMs: Long = 180_000L,
    drainStopLimit: Int = 200,
    drainCeilingMs: Long = 600_000L,
    nowNanos: () -> Long = System::nanoTime,
): DrainOutcome {
    val startedNanos = nowNanos()
    val totalDeadlineNanos = startedNanos + drainTimeoutMs * 1_000_000
    val ceilingDeadlineNanos = startedNanos + drainCeilingMs * 1_000_000
    var releasedStops = 0
    var outcome = DrainOutcome(sent = false)

    fun remainingMs(): Long {
        val stopLimitReached = releasedStops >= drainStopLimit
        val deadlineNanos = if (stopLimitReached) minOf(totalDeadlineNanos, ceilingDeadlineNanos) else ceilingDeadlineNanos
        val remaining = deadlineNanos - nowNanos()
        if (remaining <= 0) {
            val limit =
                if (deadlineNanos == ceilingDeadlineNanos) {
                    "$drainCeilingMs ms ceiling"
                } else {
                    "$drainTimeoutMs ms limit after $releasedStops synchronization stops"
                }
            bridge.cutCoordinator.failDrainUnlessVisibleOrGameOver(
                seatId,
                TimeoutException("Synchronization drain exceeded its $limit"),
            )
        }
        return (remaining / 1_000_000).coerceAtLeast(1)
    }
    while (true) {
        remainingMs()
        val step =
            drainOneCoordinatorBarrier(
                sink = sink,
                synchronizationActionId =
                    bridge
                        .takeIf { it.cutCoordinator.committedGameOverOutcome() == null }
                        ?.actionBridge(seatId)
                        ?.getPending()
                        ?.takeIf { it.state.kind == leyline.bridge.handoff.PendingActionKind.SYNC_ONLY }
                        ?.actionId,
                drainCommitted = { bridge.cutCoordinator.drain(seatId) },
                completeSynchronization = { actionId ->
                    bridge.actionBridge(seatId).completeSyncPass(actionId).also { if (it) releasedStops++ }
                },
                awaitNext = {
                    MatchReceiveProbe.inPhase(MatchReceivePhase.CoordinatorWait) {
                        bridge.awaitPriorityWithTimeout(minOf(remainingMs(), bridge.priorityWaitMs))
                    }
                    remainingMs()
                },
                failDelivery = bridge.cutCoordinator::failDelivery,
                betweenBatches = betweenBatches,
                beforeDrain = beforeDrain,
            )
        outcome =
            DrainOutcome(
                sent = outcome.sent || step.sent,
                synchronization = step.synchronization,
                synchronizationActionId = step.synchronizationActionId,
            )
        if (step.synchronization != SynchronizationDrain.Completed) return outcome
        val pending = bridge.actionBridge(seatId).getPending()
        if (pending?.state?.kind != leyline.bridge.handoff.PendingActionKind.SYNC_ONLY) return outcome
    }
}

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

/** Deliver already-committed coordinator batches and terminalize on sink failure. */
internal fun deliverCommittedCoordinatorBatches(
    sink: GreMessageSink,
    bridge: GameBridge,
    seatId: SeatId,
) {
    val batches = bridge.cutCoordinator.drain(seatId)
    try {
        batches.forEach(sink::sendBundledGRE)
    } catch (ex: Exception) {
        bridge.cutCoordinator.failDelivery(ex)
    }
}

/**
 * Session identity. Logical protocol allocation remains inside tentative runtime cuts.
 */
interface SessionCounters {
    val seatId: SeatId
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

    fun onEffectCost(greMsg: ClientToGREMessage) {}

    fun onCancelAction(greMsg: ClientToGREMessage) {}

    fun onCastingTimeOptions(greMsg: ClientToGREMessage) {}

    fun onAssignDamage(greMsg: ClientToGREMessage) {}

    fun onOptionalActionResp(greMsg: ClientToGREMessage) {}

    fun onNumericInputResp(greMsg: ClientToGREMessage) {}

    fun onStringInputResp(greMsg: ClientToGREMessage) {}

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
 * A non-null `gameBridge` is NOT part of this contract. Sessions that drive
 * game logic ([MatchSession]) implement that separately via [GameOps].
 *
 * [HandlerConstructorContractTest] pins each concrete handler's
 * primary-constructor parameter types to this narrow-interface contract.
 */
interface SessionOps :
    GreMessageSink,
    SessionCounters,
    ActionReceiver {
    val matchId: String
}

/**
 * Game-bound session contract — adds the non-null game state surface
 * to [SessionOps]. Implemented by [MatchSession]; not implemented by
 * [FamiliarSession].
 */
interface GameOps : SessionOps {
    val gameBridge: GameBridge
}
