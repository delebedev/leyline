package leyline.bridge.handoff

import forge.game.Game
import forge.game.spellability.SpellAbility
import leyline.DevCheck
import leyline.bridge.BridgeTimeoutDiagnostic
import leyline.bridge.PriorityActionCandidates
import leyline.bridge.types.PrioritySignal
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.Action
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe bridge between the blocking engine game loop and async Netty handlers.
 *
 * When the engine reaches a priority stop (via [leyline.bridge.forge.PlayerController.chooseSpellAbilityToPlay]),
 * it calls [awaitAction] which blocks the game thread. The match cut runtime publishes the
 * complete window before signalling and resolves a value response after the engine wakes.
 *
 * Sibling to [InteractivePromptBridge] which handles non-priority prompts (targeting, sacrifice).
 * One pending action at a time — the engine is single-threaded per game.
 */
class GameActionBridge(
    @Volatile private var timeoutMs: Long? = DEFAULT_TIMEOUT_MS,
    val prioritySignal: PrioritySignal? = null,
    private val windowRuntime: ActionWindowRuntime? = null,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val DISCONNECT_TIMEOUT_MS = 300_000L
        internal const val ENGINE_PASS_TOKEN = 0L
        private val log = LoggerFactory.getLogger(GameActionBridge::class.java)
    }

    fun getTimeoutMs(): Long? = timeoutMs

    fun setTimeoutMs(ms: Long?) {
        timeoutMs = ms
    }

    /**
     * Epoch-millis deadline for the current pending action, or null when no action
     * is pending or the game is paused. Frontend uses this to display a countdown timer.
     */
    @Volatile
    var deadlineMs: Long? = null
        private set

    /** True when the human player has paused the game (PvAI only). */
    @Volatile
    var paused: Boolean = false
        private set

    fun setPaused(value: Boolean) {
        paused = value
        if (value) {
            // Freeze the deadline — timer stops ticking
            deadlineMs = null
        }
    }

    /**
     * Reset the deadline from now, used when resuming from pause
     * so the player gets a fresh timeout window.
     */
    fun resetDeadline() {
        deadlineMs = timeoutMs?.let { System.currentTimeMillis() + it }
    }

    /**
     * One executable priority offer.
     *
     * The command is created while Forge is blocked for this priority window
     * and is discarded when that window completes. It deliberately contains no
     * protocol identity; [action] is the Leyline-only projection sent to the
     * client.
     */
    data class ActionOffer(
        val action: Action,
        val command: PlayerAction,
        val stackAbilityGrpId: Int? = null,
        val forgeAbilityId: Int? = null,
        val spellGrpId: Int? = null,
        internal val castCandidates: List<SpellAbility> = emptyList(),
    )

    /**
     * One engine wait for a client answer.
     *
     * Window lifecycle — visibility, claim, completion, prompt correlation — is
     * owned by the [ActionWindowRuntime], not mirrored here. [promptGameStateId]
     * reads through to that owner.
     */
    data class PendingAction(
        val actionId: String,
        val state: PendingActionState,
        val future: CompletableFuture<ActionSubmission>,
        internal val priorityCandidates: PriorityActionCandidates? = null,
        internal val windowRuntime: ActionWindowRuntime,
    ) {
        val promptGameStateId: Int? get() = windowRuntime.promptGameStateId(actionId)
    }

    private data class AwaitedSubmission(
        val submission: ActionSubmission,
        val closeReason: WindowCloseReason,
    )

    sealed interface ActionSubmission {
        /** Token [ENGINE_PASS_TOKEN] is reserved for an engine-only pass. */
        data class RuntimeToken(
            val token: Long,
        ) : ActionSubmission
    }

    /**
     * Imperative-shell runtime for publishing and resolving one priority window.
     *
     * It is the sole authority on window lifecycle: [isVisible] and
     * [promptGameStateId] answer for the record it owns.
     */
    interface ActionWindowRuntime {
        fun publish(pending: PendingAction)

        /** True while the window is published and neither claimed nor completed. */
        fun isVisible(actionId: String): Boolean

        /** Game-state id the client must answer against, or null before one is bound. */
        fun promptGameStateId(actionId: String): Int?

        fun resolve(
            pending: PendingAction,
            submission: ActionSubmission.RuntimeToken,
        ): PlayerAction?

        fun close(
            pending: PendingAction,
            reason: WindowCloseReason,
        )

        /** Atomically retire a timed-out window against a concurrent session answer. */
        fun claimTimeout(
            pending: PendingAction,
            cause: TimeoutException,
        ): Boolean = pending.future.completeExceptionally(cause)

        /** Atomically claim timeout for a committed state-only delivery barrier. */
        fun claimSynchronizationTimeout(
            pending: PendingAction,
            cause: TimeoutException,
        ): Boolean = pending.future.completeExceptionally(cause)

        /** Complete a delivered state-only barrier with its engine-only pass. */
        fun completeSynchronization(pending: PendingAction): Boolean =
            pending.future.complete(ActionSubmission.RuntimeToken(ENGINE_PASS_TOKEN))
    }

    enum class WindowCloseReason { Answered, TimedOut, Cancelled, Failed }

    private val pending = AtomicReference<PendingAction?>(null)

    // -- Diagnostic context (set by GameLoopController after thread launch) --

    @Volatile private var diagnosticGame: Game? = null

    @Volatile private var diagnosticThread: Thread? = null

    /** Set diagnostic context for timeout messages. Called by [leyline.bridge.coord.GameLoopController]. */
    fun setDiagnosticContext(
        game: Game,
        engineThread: Thread,
    ) {
        diagnosticGame = game
        diagnosticThread = engineThread
    }

    /**
     * When set, [leyline.bridge.forge.PlayerController.chooseSpellAbilityToPlay] auto-passes
     * without blocking on the bridge. Cleared on turn boundary.
     * Matches desktop Forge "End Turn" behavior.
     */
    private val _autoPassUntilEndOfTurn = AtomicBoolean(false)
    private val synchronizationContinuation = AtomicReference(SynchronizationContinuation.Reevaluate)

    val autoPassUntilEndOfTurn: Boolean get() = _autoPassUntilEndOfTurn.get()

    fun setAutoPassUntilEndOfTurn(value: Boolean) {
        _autoPassUntilEndOfTurn.set(value)
    }

    /** Arm the next engine priority decision after a synchronization wait returned successfully. */
    internal fun armSynchronizationContinuation(continuation: SynchronizationContinuation) {
        if (continuation == SynchronizationContinuation.Reevaluate) return
        check(
            synchronizationContinuation.compareAndSet(
                SynchronizationContinuation.Reevaluate,
                continuation,
            ),
        ) { "Synchronization continuation already armed" }
    }

    internal fun consumeSynchronizationContinuation(): SynchronizationContinuation =
        synchronizationContinuation.getAndSet(SynchronizationContinuation.Reevaluate)

    /**
     * Called from the engine thread (BLOCKS until client responds or timeout).
     *
     * @param state describes the current game state context for the pending action
     * @return the player's chosen action
     */
    @Suppress("ThrowsCount")
    fun awaitAction(
        state: PendingActionState,
        priorityCandidates: PriorityActionCandidates? = null,
    ): PlayerAction {
        val configuredTimeoutMs = timeoutMs
        if (configuredTimeoutMs == 0L) {
            return PlayerAction.PassPriority
        }

        val actionId = UUID.randomUUID().toString()
        val future = CompletableFuture<ActionSubmission>()
        val runtime = checkNotNull(windowRuntime) { "Blocking action waits require ActionWindowRuntime" }
        val action = PendingAction(actionId, state, future, priorityCandidates, runtime)

        if (!pending.compareAndSet(null, action)) {
            log.warn("Action bridge already has a pending action; auto-passing")
            DevCheck.failOnAutoPass { "Action bridge already has a pending action" }
            return PlayerAction.PassPriority
        }
        try {
            runtime.publish(action)
            check(runtime.isVisible(actionId) || future.isDone) {
                "Action window runtime returned before publication completed"
            }
        } catch (ex: Exception) {
            pending.compareAndSet(action, null)
            runtime.close(action, WindowCloseReason.Failed)
            throw ex
        }
        prioritySignal?.signal()

        val effectiveTimeout = if (paused) null else configuredTimeoutMs
        deadlineMs = effectiveTimeout?.let { System.currentTimeMillis() + it }

        val awaited = awaitSubmission(action, effectiveTimeout)
        var closeReason = awaited.closeReason
        return try {
            when (val submission = awaited.submission) {
                is ActionSubmission.RuntimeToken ->
                    checkNotNull(runtime.resolve(action, submission)) { "Action runtime token did not resolve" }
            }
        } catch (ex: Exception) {
            closeReason = WindowCloseReason.Failed
            throw ex
        } finally {
            runtime.close(action, closeReason)
        }
    }

    @Suppress("ThrowsCount")
    private fun awaitSubmission(
        action: PendingAction,
        effectiveTimeout: Long?,
    ): AwaitedSubmission =
        try {
            if (effectiveTimeout == null) {
                AwaitedSubmission(action.future.get(), WindowCloseReason.Answered)
            } else {
                awaitTimedSubmission(action, effectiveTimeout)
            }
        } catch (ex: ExecutionException) {
            action.windowRuntime.close(action, WindowCloseReason.Failed)
            throw ex.cause ?: ex
        } catch (ex: Exception) {
            action.windowRuntime.close(action, WindowCloseReason.Failed)
            throw ex
        } finally {
            deadlineMs = null
            pending.compareAndSet(action, null)
        }

    private fun awaitTimedSubmission(
        action: PendingAction,
        effectiveTimeout: Long,
    ): AwaitedSubmission =
        try {
            AwaitedSubmission(
                action.future.get(effectiveTimeout, TimeUnit.MILLISECONDS),
                WindowCloseReason.Answered,
            )
        } catch (_: TimeoutException) {
            handleActionTimeout(action, effectiveTimeout)
        }

    private fun handleActionTimeout(
        action: PendingAction,
        effectiveTimeout: Long,
    ): AwaitedSubmission {
        val timeout = TimeoutException("Action window timed out")
        val claimed =
            if (action.state.kind == PendingActionKind.SYNC_ONLY) {
                action.windowRuntime.claimSynchronizationTimeout(action, timeout)
            } else {
                action.windowRuntime.claimTimeout(action, timeout)
            }
        if (!claimed) {
            return try {
                AwaitedSubmission(action.future.getNow(null) ?: action.future.get(), WindowCloseReason.Answered)
            } catch (ex: Exception) {
                throw when (ex) {
                    is ExecutionException -> ex.cause ?: ex
                    is java.util.concurrent.CompletionException -> ex.cause ?: ex
                    else -> ex
                }
            }
        }
        if (action.state.kind == PendingActionKind.SYNC_ONLY) {
            throw timeout
        }

        val diagnostic =
            BridgeTimeoutDiagnostic.buildMessage(
                bridgeName = "GameActionBridge",
                timeoutMs = effectiveTimeout,
                game = diagnosticGame,
                engineThread = diagnosticThread,
                lastContext =
                    "PendingAction(id=${action.actionId.take(8)}, phase=${action.state.phase}, " +
                        "active=${action.state.activePlayerId}, priority=${action.state.priorityPlayerId})",
            )
        log.warn("Action timed out, auto-passing\n{}", diagnostic)
        DevCheck.failOnAutoPass { "Action timed out after ${effectiveTimeout}ms" }
        return AwaitedSubmission(
            ActionSubmission.RuntimeToken(ENGINE_PASS_TOKEN),
            WindowCloseReason.TimedOut,
        )
    }

    /** Complete the exact pending window with a coordinator-owned runtime token. */
    fun submitRuntimeToken(
        actionId: String,
        token: Long,
    ): Boolean {
        val current = pending.get() ?: return false
        if (current.actionId != actionId) return false
        return current.future.complete(ActionSubmission.RuntimeToken(token))
    }

    /** Complete an engine-only synchronization stop without a client action catalog. */
    internal fun completeSyncPass(actionId: String): Boolean {
        val current = pending.get() ?: return false
        if (current.actionId != actionId || current.state.kind != PendingActionKind.SYNC_ONLY) return false
        return (
            current.windowRuntime.completeSynchronization(current)
        )
    }

    /**
     * Get the current pending action for client broadcast. Returns null if no action
     * is pending.
     *
     * A pending action whose future is already completed (submitted but not yet
     * cleared by the engine thread's `finally` block) is NOT considered pending —
     * the engine is still in its cleanup path and hasn't reached the next priority
     * stop. Without this check, [GameBridge.awaitPriorityWithTimeout] can see a
     * stale pending action and return prematurely, causing the session to send
     * state before the engine processes triggers (e.g. modal ETB).
     */
    fun getPending(): PendingAction? {
        val p = pending.get() ?: return null
        if (p.future.isDone) return null
        return if (p.windowRuntime.isVisible(p.actionId)) p else null
    }

    internal fun exactPending(actionId: String): PendingAction? = pending.get()?.takeIf { it.actionId == actionId && !it.future.isDone }

    /**
     * Cancel any pending action (e.g. on disconnect / game reset).
     */
    fun cancelPending() {
        synchronizationContinuation.set(SynchronizationContinuation.Reevaluate)
        val current = pending.getAndSet(null)
        current?.future?.cancel(true)
    }

    /** Wake an engine thread waiting in this bridge with the coordinator's terminal cause. */
    fun failPending(cause: Throwable) {
        synchronizationContinuation.set(SynchronizationContinuation.Reevaluate)
        pending.getAndSet(null)?.future?.completeExceptionally(cause)
    }
}
