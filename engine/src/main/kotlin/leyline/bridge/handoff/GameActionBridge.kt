package leyline.bridge.handoff

import forge.game.Game
import forge.game.spellability.SpellAbility
import leyline.DevCheck
import leyline.bridge.BridgeTimeoutDiagnostic
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ForgePlayerId
import leyline.bridge.types.PrioritySignal
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe bridge between the blocking engine game loop and async Netty handlers.
 *
 * When the engine reaches a priority stop (via [leyline.bridge.forge.PlayerController.chooseSpellAbilityToPlay]),
 * it calls [awaitAction] which blocks the game thread. The message handler broadcasts state to the
 * client, and when the client responds (cast, pass, attack, etc.), [submitAction] completes
 * the future so the engine resumes.
 *
 * Sibling to [InteractivePromptBridge] which handles non-priority prompts (targeting, sacrifice).
 * One pending action at a time — the engine is single-threaded per game.
 */
class GameActionBridge(
    @Volatile private var timeoutMs: Long? = DEFAULT_TIMEOUT_MS,
    val prioritySignal: PrioritySignal? = null,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val DISCONNECT_TIMEOUT_MS = 300_000L
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
    )

    data class PendingAction(
        val actionId: String,
        val state: PendingActionState,
        val future: CompletableFuture<PlayerAction>,
        @Volatile var promptGameStateId: Int? = null,
        @Volatile var actionCatalog: Map<ActionResponseKey, List<ActionOffer>>? = null,
    )

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
        val configuredTimeoutMs = timeoutMs
        if (configuredTimeoutMs == 0L) {
            return PlayerAction.PassPriority
        }

        val actionId = UUID.randomUUID().toString()
        val future = CompletableFuture<PlayerAction>()
        val action = PendingAction(actionId, state, future)

        if (!pending.compareAndSet(null, action)) {
            log.warn("Action bridge already has a pending action; auto-passing")
            DevCheck.failOnAutoPass { "Action bridge already has a pending action" }
            return PlayerAction.PassPriority
        }
        prioritySignal?.signal()

        val effectiveTimeout = if (paused) null else configuredTimeoutMs
        deadlineMs = effectiveTimeout?.let { System.currentTimeMillis() + it }

        return try {
            if (effectiveTimeout == null) {
                future.get()
            } else {
                future.get(effectiveTimeout, TimeUnit.MILLISECONDS)
            }
        } catch (_: TimeoutException) {
            val diagnostic =
                BridgeTimeoutDiagnostic.buildMessage(
                    bridgeName = "GameActionBridge",
                    timeoutMs = checkNotNull(effectiveTimeout),
                    game = diagnosticGame,
                    engineThread = diagnosticThread,
                    lastContext =
                        "PendingAction(id=${actionId.take(8)}, phase=${state.phase}, " +
                            "active=${state.activePlayerId}, priority=${state.priorityPlayerId})",
                )
            log.warn("Action timed out, auto-passing\n{}", diagnostic)
            DevCheck.failOnAutoPass { "Action timed out after ${effectiveTimeout}ms" }
            PlayerAction.PassPriority
        } catch (ex: Exception) {
            log.warn("Action await failed: ${ex.message}, auto-passing")
            DevCheck.failOnAutoPass { "Action await failed: ${ex.message}" }
            PlayerAction.PassPriority
        } finally {
            deadlineMs = null
            pending.set(null)
        }
    }

    /**
     * Called from the Netty handler. Completes the pending action future
     * so the blocked engine thread can resume.
     *
     * @return true if the action was matched and completed
     */
    fun submitAction(
        actionId: String,
        action: PlayerAction,
    ): Boolean {
        val current = pending.get() ?: return false
        if (current.actionId != actionId) {
            log.warn("Action ID mismatch: expected=${current.actionId}, got=$actionId")
            return false
        }
        return current.future.complete(action)
    }

    /**
     * Atomically bind an executable priority catalog to its outbound prompt.
     *
     * Call this before the corresponding ActionsAvailableReq becomes visible.
     * A later AAR for the same still-blocked priority window supersedes the
     * previous catalog atomically; responses to the earlier game-state id then
     * cannot resolve.
     */
    fun bindActionCatalog(
        actionId: String,
        gameStateId: Int,
        offers: List<ActionOffer>,
    ): Boolean {
        val groupedOffers = offers.groupBy { ActionResponseKey.from(it.action) }
        val hasAmbiguousKey =
            groupedOffers.values.any { variants ->
                val identities = variants.map { Triple(it.command, it.stackAbilityGrpId, it.forgeAbilityId) }.distinct()
                identities.size > 1 && variants.map { it.action }.distinct().size == 1
            }
        if (hasAmbiguousKey) {
            log.warn(
                "Refusing ambiguous action catalog with duplicate response keys: {}",
                groupedOffers.filterValues { variants ->
                    variants.map { Triple(it.command, it.stackAbilityGrpId, it.forgeAbilityId) }.distinct().size > 1
                },
            )
            return false
        }
        val catalog = groupedOffers

        val current =
            pending.get() ?: run {
                log.warn("Cannot bind action catalog: no pending action")
                return false
            }
        synchronized(current) {
            if (current.actionId != actionId) {
                log.warn("Cannot bind action catalog: pending window was superseded")
                return false
            }
            if (current.future.isDone) {
                log.warn("Cannot bind action catalog: pending window already completed")
                return false
            }
            current.actionCatalog = catalog
            current.promptGameStateId = gameStateId
            return true
        }
    }

    /** Resolve a response only against the catalog bound to its pending window. */
    fun resolveOfferedAction(
        pendingAction: PendingAction,
        responseGameStateId: Int,
        action: Action,
    ): ActionOffer? {
        if (!acceptsResponse(pendingAction, responseGameStateId)) return null
        val catalog = pendingAction.actionCatalog ?: return null
        val keyedMatches = catalog[ActionResponseKey.from(action)].orEmpty()
        chooseExecutionIdentity(keyedMatches, action)?.let { return it }
        val partialMatches = catalog.values.flatten().filter { offer -> matchesPartialResponse(offer.action, action) }
        return chooseExecutionIdentity(partialMatches, action)
    }

    private fun chooseExecutionIdentity(
        offers: List<ActionOffer>,
        response: Action,
    ): ActionOffer? {
        val identities = offers.distinctBy { listOf(it.command, it.stackAbilityGrpId, it.forgeAbilityId, it.spellGrpId) }
        val exactPayloadMatches = identities.filter { it.action == response }
        if (exactPayloadMatches.size == 1) return exactPayloadMatches.single()
        if (identities.size == 1) return identities.first()

        val bestScore = identities.maxOfOrNull { selectorMatchScore(it.action, response) } ?: return null
        return identities.filter { selectorMatchScore(it.action, response) == bestScore }.singleOrNull()
    }

    private fun selectorMatchScore(
        offer: Action,
        response: Action,
    ): Int =
        listOf(
            offer.grpId to response.grpId,
            offer.abilityGrpId to response.abilityGrpId,
            offer.alternativeGrpId to response.alternativeGrpId,
        ).count { (offered, returned) -> offered != 0 && returned != 0 && offered == returned }

    private fun matchesPartialResponse(
        offer: Action,
        response: Action,
    ): Boolean =
        offer.actionType == response.actionType &&
            offer.instanceId == response.instanceId &&
            (response.grpId == 0 || offer.grpId == 0 || offer.grpId == response.grpId) &&
            (response.abilityGrpId == 0 || offer.abilityGrpId == 0 || offer.abilityGrpId == response.abilityGrpId) &&
            (response.alternativeGrpId == 0 || offer.alternativeGrpId == 0 || offer.alternativeGrpId == response.alternativeGrpId)

    fun acceptsResponse(
        pendingAction: PendingAction,
        responseGameStateId: Int,
    ): Boolean {
        if (responseGameStateId == 0) return true
        return pendingAction.promptGameStateId == responseGameStateId
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

/** Stable protocol selectors; excludes client-populated payment and auto-tap detail. */
data class ActionResponseKey(
    val type: ActionType,
    val instanceId: Int,
    val grpId: Int,
    val abilityGrpId: Int,
    val alternativeGrpId: Int,
) {
    companion object {
        @Suppress("ElseCaseInsteadOfExhaustiveWhen")
        fun from(action: Action) =
            when (action.actionType) {
                ActionType.Pass, ActionType.FloatMana -> ActionResponseKey(action.actionType, 0, 0, 0, 0)
                ActionType.Play_add3, ActionType.PlayMdfc, ActionType.SpecialTurnFaceUp_add3 ->
                    ActionResponseKey(action.actionType, action.instanceId, 0, 0, 0)
                else ->
                    ActionResponseKey(
                        action.actionType,
                        action.instanceId,
                        action.grpId,
                        action.abilityGrpId,
                        action.alternativeGrpId,
                    )
            }
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
    val kind: PendingActionKind = PendingActionKind.PRIORITY,
)

enum class PendingActionKind {
    PRIORITY,
    DECLARE_ATTACKERS,
    DECLARE_BLOCKERS,
}

/** A game entity that can be targeted: card or player. */
sealed class Target {
    data class Card(
        val cardId: ForgeCardId,
    ) : Target()

    data class Player(
        val playerId: ForgePlayerId,
    ) : Target()
}

/**
 * Actions a player can take when they have priority.
 */
sealed class PlayerAction {
    data object PassPriority : PlayerAction()

    data class CastSpell(
        val cardId: ForgeCardId,
        val abilityId: Int? = null,
        val targets: List<Target> = emptyList(),
        val ability: SpellAbility? = null,
    ) : PlayerAction()

    data class ActivateAbility(
        val cardId: ForgeCardId,
        val abilityId: Int,
        val targets: List<Target> = emptyList(),
        val ability: SpellAbility? = null,
    ) : PlayerAction()

    data class ActivateMana(
        val cardId: ForgeCardId,
        val abilityId: Int? = null,
        val selectedColor: Byte? = null,
        val ability: SpellAbility? = null,
    ) : PlayerAction()

    data class PlayLand(
        val cardId: ForgeCardId,
    ) : PlayerAction()

    data class DeclareAttackers(
        val attackerIds: List<ForgeCardId>,
        val attackAlternativeByAttacker: Map<ForgeCardId, Int> = emptyMap(),
        val defender: Target? = null,
        val defenderByAttacker: Map<ForgeCardId, Target> = emptyMap(),
    ) : PlayerAction()

    data class DeclareBlockers(
        val blockAssignments: Map<ForgeCardId, ForgeCardId>,
    ) : PlayerAction()

    /** Auto-pass all remaining priority in this turn (matches desktop "End Turn" button). */
    data object EndTurn : PlayerAction()
}
