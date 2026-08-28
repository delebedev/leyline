package leyline.game

import forge.game.Game
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.types.SeatId
import leyline.game.bundle.AbilityExhaustionFactsCapture
import leyline.game.bundle.MechanicSourceFactsCapture
import leyline.game.bundle.PersistentFeedFactsCapture
import leyline.game.event.FrameEventLog
import leyline.game.mapping.StateFrameInput
import leyline.game.mapping.StateProjectionCompiler
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.game.state.ProjectionState
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Shared test helpers for GameBridge-based tests.
 *
 * Race-free implementations shared by conformance and integration tests.
 */

/**
 * Build and install a Full GSM transition, including the next diff baseline.
 *
 * Returns the captured snapshot — tests that drive subsequent `buildDiff`
 * calls directly (not via a BundleBuilder) can pass it as `prev` explicitly.
 */
fun GameBridge.seedDiffBaseline(
    game: Game,
    gameStateId: Int = 0,
): GsmSnapshot =
    synchronized(projectionBuildLock) {
        val priorProjection = projectionStateSnapshot()
        val (snap, capturedProjection) =
            editProjection(priorProjection) {
                GsmSnapshot.capture(game, this, "", gameStateId)
            }
        val events = closeBundleFrame()
        val promptFacts = materializePromptProjectionFacts()
        val result =
            StateProjectionCompiler.compileOneViewer(
                environment = stateProjectionEnvironment,
                input =
                    StateFrameInput(
                        gameStateId = gameStateId,
                        snapshot = snap,
                        previousSnapshot = null,
                        events = events,
                        promptFacts = promptFacts,
                        persistentFeedFacts =
                            PersistentFeedFactsCapture.capture(snap, promptFacts, this, stateProjectionEnvironment),
                        effectFacts = materializeEffectProjectionFacts(),
                        mechanicSourceFacts = MechanicSourceFactsCapture.capture(this, events.events),
                        abilityExhaustionFacts = AbilityExhaustionFactsCapture.capture(snap, this),
                        updateType = wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate.SendAndRecord,
                        viewingSeatId = seating.humanSeat.value,
                        revealForSeat = null,
                    ),
                prior = capturedProjection.copy(revision = priorProjection.revision),
            )
        commitProjection(result.transition)
        snap
    }

/** Compile one observational projection from an explicit snapshot without installing it. */
fun GameBridge.projectSnapshotForTest(
    snap: GsmSnapshot,
    gameStateId: Int = 0,
    viewingSeatId: Int = 0,
    events: FrameEventLog = FrameEventLog.EMPTY,
    projectionState: ProjectionState = projectionStateSnapshot(),
): StateProjectionCompiler.Result {
    val promptFacts = materializePromptProjectionFacts()
    return StateProjectionCompiler.compileOneViewer(
        environment = stateProjectionEnvironment,
        input =
            StateFrameInput(
                gameStateId = gameStateId,
                snapshot = snap,
                previousSnapshot = null,
                events = events,
                promptFacts = promptFacts,
                persistentFeedFacts = PersistentFeedFactsCapture.capture(snap, promptFacts, this, stateProjectionEnvironment),
                effectFacts = materializeEffectProjectionFacts(),
                mechanicSourceFacts = MechanicSourceFactsCapture.capture(this, events.events),
                abilityExhaustionFacts = AbilityExhaustionFactsCapture.capture(snap, this),
                updateType = wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate.SendAndRecord,
                viewingSeatId = viewingSeatId,
                revealForSeat = null,
            ),
        prior = projectionState,
    )
}

/**
 * Wait for a pending action whose actionId differs from [previousId].
 * Returns null on timeout (default 15s).
 *
 * The Thread.sleep is a poll interval, not a race. NoThreadSleepInTests targets
 * sleeps in test bodies as correctness proxies; this is deadlined infrastructure.
 */
@Suppress("NoThreadSleepInTests")
fun awaitFreshPending(
    b: GameBridge,
    previousId: String?,
    timeoutMs: Long = 15_000,
): GameActionBridge.PendingAction? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val p = b.actionBridge(SeatId(1)).getPending()
        if (p != null && p.actionId != previousId && !p.future.isDone) return p
        Thread.sleep(5)
    }
    return null
}

/**
 * Pass priority until the game reaches Main1.
 *
 * Uses the pending action's phase (set when the engine blocks) instead of
 * polling `game.phaseHandler.phase` -- eliminates a race where the live phase
 * is checked before the pending is found, causing an accidental pass at Main1.
 */

/**
 * Advance the engine to a phase matching [predicate] by submitting one
 * PassPriority at a time via the bridge. No session progression policy —
 * each pass is a single engine step, so there is no phase overshoot.
 *
 * Returns the [GameActionBridge.PendingAction] at the target phase
 * (the engine is blocked, so game state is stable for assertions).
 *
 * Combat phases are safe: submitting PassPriority during declareAttackers
 * / declareBlockers means "no attackers / no blockers" — the engine
 * continues to the next phase without hanging.
 */
fun advanceTo(
    b: GameBridge,
    maxPasses: Int = 50,
    timeoutMs: Long = 15_000,
    predicate: (phase: String, turn: Int) -> Boolean,
    onSynchronization: (() -> Unit)? = null,
): GameActionBridge.PendingAction {
    val game = b.getGame() ?: error("Game was not initialised")
    var lastId: String? = null
    repeat(maxPasses) {
        val pending =
            awaitFreshPending(b, lastId, timeoutMs)
                ?: error("Timed out waiting for priority (phase=${game.phaseHandler.phase}, turn=${game.phaseHandler.turn})")
        if (predicate(pending.state.phase, pending.state.turn)) return pending
        if (pending.state.kind == leyline.bridge.handoff.PendingActionKind.SYNC_ONLY) {
            checkNotNull(onSynchronization) { "SyncOnly advancement requires a delivery observer" }()
            lastId = pending.actionId
            return@repeat
        }
        val claim =
            checkNotNull(
                b.cutCoordinator.claimPriorityResponse(
                    pending.actionId,
                    pending.promptGameStateId ?: 0,
                    Action.newBuilder().setActionType(ActionType.Pass).build(),
                    defer = false,
                ),
            )
        check(b.cutCoordinator.completeActionClaim(claim.actionClaim))
        lastId = pending.actionId
    }
    error(
        "Max passes ($maxPasses) exceeded advancing to target phase (current: ${game.phaseHandler.phase}, turn ${game.phaseHandler.turn})",
    )
}

/** Advance to a specific [phase], optionally on a specific [turn]. */
fun advanceToPhase(
    b: GameBridge,
    phase: String,
    turn: Int? = null,
    maxPasses: Int = 50,
) = advanceTo(b, maxPasses, predicate = { p, t -> p == phase && (turn == null || t == turn) })

/** Advance to COMBAT_DECLARE_ATTACKERS. */
fun advanceToCombat(
    b: GameBridge,
    turn: Int? = null,
) = advanceToPhase(b, "COMBAT_DECLARE_ATTACKERS", turn)

/** Advance to MAIN2. */
fun advanceToMain2(
    b: GameBridge,
    turn: Int? = null,
) = advanceToPhase(b, "MAIN2", turn)

fun advanceToMain1(
    b: GameBridge,
    maxPasses: Int = 20,
) {
    advanceToPhase(b, "MAIN1", maxPasses = maxPasses)
}
