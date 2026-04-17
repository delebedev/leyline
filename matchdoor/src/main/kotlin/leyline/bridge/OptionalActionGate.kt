package leyline.bridge

import forge.game.card.Card
import forge.game.trigger.WrappedAbility
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Narrow access surface exposed by [WebPlayerController] to coordinators and helpers.
 *
 * Coordinators receive an `OwnerContext` rather than a full [WebPlayerController]
 * reference. A full reference would let any coordinator reach any override, any
 * private helper, any PCHuman-inherited method — and the boundary would erode
 * within two refactors. Keeping the surface narrow is a compile-time fence: if a
 * coordinator needs a field or callback not on this interface, that is a signal
 * to discuss adding it rather than to widen access.
 *
 * Grows one field at a time, driven by actual coordinator need. Do not
 * pre-populate with fields a coordinator might want someday.
 *
 * See the [WebPlayerController] class KDoc for the state-ownership rules that
 * decide what belongs here vs. on the class vs. on [InteractivePromptBridge].
 */
interface OwnerContext {
    /** Pending optional-action prompt (set by [OptionalActionGate], read by session handlers). */
    var pendingOptionalAction: WebPlayerController.OptionalActionPrompt?

    /** Pending manual combat-damage assignment (set by [PriorityLoopCoordinator], read by `CombatHandler`). */
    var pendingDamageAssignment: WebPlayerController.DamageAssignmentPrompt?

    /** Batched damage assignments cached by `CombatHandler.onAssignDamage` for subsequent attackers. */
    val damageAssignCache: MutableMap<ForgeCardId, MutableMap<Card?, Int>>

    /** Client auto-pass state (full-control flag, phase stops). */
    val autoPassState: ClientAutoPassState?

    /** Append a priority decision to the bounded log backing [WebPlayerController.decisionLog]. */
    fun recordDecision(decision: PriorityDecision)

    /** Invoke the `onStateChanged` callback so the session layer can ship updated state. */
    fun notifyStateChanged()
}

/**
 * Owns the [WebPlayerController.pendingOptionalAction] future lifecycle for the
 * three override sites that share it (`confirmTrigger`, `playSaFromPlayEffect`,
 * `payCostToPreventEffect`).
 *
 * Each site used to assemble the future, assign the pending field, signal the
 * priority bridge, `get()` the future with a timeout, and clear the field in a
 * `finally` — identical protocol, three copies. Consolidating it here removes
 * duplication and turns the field-clear invariant into a single point of audit.
 *
 * Threading: [await] runs on the Forge engine thread. It blocks that thread
 * until the Netty session thread completes the future via
 * [leyline.match.OptionalActionHandler.onOptionalActionResp].
 */
class OptionalActionGate(
    private val owner: OwnerContext,
    private val actionBridge: GameActionBridge?,
) {
    private val log = LoggerFactory.getLogger(OptionalActionGate::class.java)

    /**
     * Post a pending optional-action prompt, block the engine thread until the
     * client responds or the action timeout elapses, and return the accept/decline
     * decision. On timeout, returns [defaultOnTimeout] and logs a warning.
     *
     * @param wrapper the wrapping Forge ability (null for non-trigger prompts)
     * @param hostCard the card the prompt is about (null when unknown)
     * @param forceSnapshotBeforePrompt when true, the session layer emits a full
     *   GSM before the prompt — needed for mid-resolution prompts where the
     *   client has not yet seen the pre-prompt state transition
     * @param defaultOnTimeout the value to return if the future times out (true for
     *   sites where auto-accepting is the safe fallback, false where auto-declining is)
     * @param logContext human-readable tag for timeout log lines (e.g. the override name)
     */
    fun await(
        wrapper: WrappedAbility? = null,
        hostCard: Card?,
        forceSnapshotBeforePrompt: Boolean = false,
        defaultOnTimeout: Boolean,
        logContext: String,
    ): Boolean {
        val future = CompletableFuture<Boolean>()
        owner.pendingOptionalAction = WebPlayerController.OptionalActionPrompt(
            wrapper = wrapper,
            hostCard = hostCard,
            future = future,
            forceSnapshotBeforePrompt = forceSnapshotBeforePrompt,
        )
        actionBridge?.prioritySignal?.signal()

        return try {
            val timeoutMs = actionBridge?.getTimeoutMs() ?: DEFAULT_TIMEOUT_MS
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            val action = if (defaultOnTimeout) "auto-accepting" else "declining"
            log.warn("{}: timeout/error for {} — {}", logContext, hostCard?.name, action, e)
            defaultOnTimeout
        } finally {
            owner.pendingOptionalAction = null
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }
}
