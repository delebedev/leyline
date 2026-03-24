package leyline.bridge

import forge.game.Game
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe bridge between the blocking engine game loop and async WebSocket handlers.
 *
 * When the engine reaches a priority stop (via [WebPlayerController.chooseSpellAbilityToPlay]),
 * it calls [awaitAction] which blocks the game thread. The WS handler broadcasts state to the
 * client, and when the client responds (cast, pass, attack, etc.), [submitAction] completes
 * the future so the engine resumes.
 *
 * Sibling to [InteractivePromptBridge] which handles non-priority prompts (targeting, sacrifice).
 * One pending action at a time — the engine is single-threaded per game.
 */
class GameActionBridge(
    @Volatile private var timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val prioritySignal: PrioritySignal? = null,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val DISCONNECT_TIMEOUT_MS = 300_000L
        private val log = LoggerFactory.getLogger(GameActionBridge::class.java)
    }

    fun getTimeoutMs(): Long = timeoutMs

    fun setTimeoutMs(ms: Long) {
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
        deadlineMs = System.currentTimeMillis() + timeoutMs
    }

    data class PendingAction(
        val actionId: String,
        val state: PendingActionState,
        val future: CompletableFuture<PlayerAction>,
    )

    private val pending = AtomicReference<PendingAction?>(null)

    // -- Diagnostic context (set by GameLoopController after thread launch) --

    @Volatile private var diagnosticGame: Game? = null

    @Volatile private var diagnosticThread: Thread? = null

    /** Set diagnostic context for timeout messages. Called by [GameLoopController]. */
    fun setDiagnosticContext(game: Game, engineThread: Thread) {
        diagnosticGame = game
        diagnosticThread = engineThread
    }

    /**
     * When set, [WebPlayerController.chooseSpellAbilityToPlay] auto-passes
     * without blocking on the bridge. Cleared on turn boundary.
     * Matches desktop Forge "End Turn" behavior.
     */
    private val _autoPassUntilEndOfTurn = AtomicBoolean(false)

    val autoPassUntilEndOfTurn: Boolean get() = _autoPassUntilEndOfTurn.get()

    fun setAutoPassUntilEndOfTurn(value: Boolean) {
        _autoPassUntilEndOfTurn.set(value)
    }

    /**
     * Called from the engine thread (BLOCKS until client responds or timeout).
     *
     * @param state describes the current game state context for the pending action
     * @return the player's chosen action
     */
    fun awaitAction(state: PendingActionState): PlayerAction {
        if (timeoutMs <= 0L) {
            return PlayerAction.PassPriority
        }

        val actionId = UUID.randomUUID().toString()
        val future = CompletableFuture<PlayerAction>()
        val action = PendingAction(actionId, state, future)

        if (!pending.compareAndSet(null, action)) {
            log.warn("Action bridge already has a pending action; auto-passing")
            return PlayerAction.PassPriority
        }
        prioritySignal?.signal()

        val effectiveTimeout = if (paused) Long.MAX_VALUE else timeoutMs
        deadlineMs = if (paused) null else System.currentTimeMillis() + effectiveTimeout

        return try {
            future.get(effectiveTimeout, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            val diagnostic = BridgeTimeoutDiagnostic.buildMessage(
                bridgeName = "GameActionBridge",
                timeoutMs = effectiveTimeout,
                game = diagnosticGame,
                engineThread = diagnosticThread,
                lastContext = "PendingAction(id=${actionId.take(8)}, phase=${state.phase}, " +
                    "active=${state.activePlayerId}, priority=${state.priorityPlayerId})",
            )
            log.warn("Action timed out, auto-passing\n{}", diagnostic)
            PlayerAction.PassPriority
        } catch (ex: Exception) {
            log.warn("Action await failed: ${ex.message}, auto-passing")
            PlayerAction.PassPriority
        } finally {
            deadlineMs = null
            pending.set(null)
        }
    }

    /**
     * Called from the WS handler coroutine. Completes the pending action future
     * so the blocked engine thread can resume.
     *
     * @return true if the action was matched and completed
     */
    fun submitAction(actionId: String, action: PlayerAction): Boolean {
        val current = pending.get() ?: return false
        if (current.actionId != actionId) {
            log.warn("Action ID mismatch: expected=${current.actionId}, got=$actionId")
            return false
        }
        return current.future.complete(action)
    }

    /**
     * Get the current pending action for WS broadcast. Returns null if no action
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
        return if (p.future.isDone) null else p
    }

    /**
     * Cancel any pending action (e.g. on disconnect / game reset).
     */
    fun cancelPending() {
        val current = pending.getAndSet(null)
        current?.future?.cancel(true)
    }
}

/**
 * Describes the game context when the engine is waiting for a player action.
 */
data class PendingActionState(
    val phase: String,
    val turn: Int,
    val activePlayerId: Int,
    val priorityPlayerId: Int,
)

/** A game entity that can be targeted: card or player. */
sealed class Target {
    data class Card(val cardId: ForgeCardId) : Target()
    data class Player(val playerId: ForgePlayerId) : Target()
}

/**
 * Actions a player can take when they have priority.
 */
sealed class PlayerAction {
    data object PassPriority : PlayerAction()
    data class CastSpell(val cardId: ForgeCardId, val abilityId: Int? = null, val targets: List<Target> = emptyList()) : PlayerAction()
    data class ActivateAbility(val cardId: ForgeCardId, val abilityId: Int, val targets: List<Target> = emptyList()) : PlayerAction()
    data class ActivateMana(val cardId: ForgeCardId) : PlayerAction()
    data class PlayLand(val cardId: ForgeCardId) : PlayerAction()
    data class DeclareAttackers(val attackerIds: List<ForgeCardId>, val defender: Target? = null) : PlayerAction()
    data class DeclareBlockers(val blockAssignments: Map<ForgeCardId, ForgeCardId>) : PlayerAction()

    /** Auto-pass all remaining priority in this turn (matches desktop "End Turn" button). */
    data object EndTurn : PlayerAction()
}
