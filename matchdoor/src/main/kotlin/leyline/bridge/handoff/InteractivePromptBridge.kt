package leyline.bridge.handoff

import forge.game.Game
import forge.game.spellability.SpellAbility
import leyline.DevCheck
import leyline.bridge.BridgeTimeoutDiagnostic
import leyline.bridge.coord.GameLoopPoller
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.PrioritySignal
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.PromptChoiceDto
import leyline.bridge.types.PromptOptionDto
import leyline.bridge.types.SeatId
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe bridge between the blocking engine thread and async Netty handlers.
 *
 * When the engine needs interactive input (choose cards, pick option, etc.),
 * [requestChoice] blocks the engine thread on a [CompletableFuture]. The message
 * handler sends a prompt to the client, and when the client responds, [submitResponse]
 * completes the future so the engine resumes.
 *
 * One pending prompt at a time — the engine is single-threaded.
 */
class InteractivePromptBridge(
    private val timeoutMs: Long? = DEFAULT_TIMEOUT_MS,
    private val prioritySignal: PrioritySignal? = null,
) {
    /**
     * Forge-id → leyline iid lookup. Set by [leyline.game.state.GameBridge]
     * after construction (the bridge is created before its owning GameBridge
     * is fully initialised, so a setter is the lowest-coupling way to wire
     * this). Used for record-time iid resolution of pending TargetSpec
     * entries — see [PendingTarget.affectorInstanceIdAtRecord].
     */
    @Volatile
    var forgeIidResolver: ((ForgeCardId) -> InstanceId)? = null

    @Volatile
    var instanceIdReservoir: (() -> InstanceId)? = null

    @Volatile
    var timeoutListener: (() -> Unit)? = null

    /**
     * Typed per-seat journal of prompt side-effects. Coordinators record
     * [PromptSideEffect] entries on the engine thread; consumers
     * ([GameEventCollector], [StateMapper], [leyline.bridge.coord.CostPaymentCoordinator]) drain
     * them during GSM assembly.
     */
    val journal: PromptJournal = PromptJournal()

    // --- Pending TargetSpec data (captured during selectTargetsInteractively) ---

    /**
     * Pending target record: spell/ability source ID + name, the targeted entity, 1-based group index.
     *
     * Exactly one of [targetForgeCardId] (card target) or [targetSeatId] (player target) is non-null.
     * [isTriggeredAbility] flips the affector iid from the spell card's iid to the synthesised
     * stack-resident-ability iid via [leyline.game.mapping.FrameIdResolver.stackAbilityForgeId].
     *
     * [forgeAbilityId] is the Forge `SpellAbility.id` for the targeting
     * spell/ability. It drives TargetSpec abilityGrpId resolution; for
     * triggered abilities it also drives stack-ability iid resolution when
     * [affectorInstanceIdAtRecord] is the deferred-resolution sentinel `0`.
     *
     * [affectorInstanceIdAtRecord] is the spell/ability iid as it stood at
     * record time (when the player picked targets and the spell was on the
     * stack). Multi-target spells (e.g. Bite Down) emit one TargetSpec per
     * group across multiple GSM drains; the spell's live iid changes when it
     * resolves Stack→Graveyard, so re-deriving the affector iid at emission
     * time would split the per-group TargetSpecs across two iids. Freezing
     * the iid here keeps every group of the same cast pointing at the same
     * stack-resident affector.
     */
    data class PendingTarget(
        val spellForgeCardId: Int,
        val spellName: String,
        val index: Int,
        val affectorInstanceIdAtRecord: Int,
        val targetForgeCardId: Int? = null,
        val targetSeatId: Int? = null,
        val isTriggeredAbility: Boolean = false,
        val abilityGrpId: Int? = null,
        val promptId: Int? = null,
        /** Forge `SpellAbility.id` for the targeting spell/ability. */
        val forgeAbilityId: Int = 0,
    )

    private val pendingTargetSpecs = ConcurrentLinkedQueue<PendingTarget>()
    private val targetSpecIndexCounter = AtomicInteger(0)

    fun addPendingTargetSpec(spec: PendingTarget) {
        pendingTargetSpecs.add(spec)
    }

    fun nextTargetSpecIndex(): Int = targetSpecIndexCounter.incrementAndGet()

    fun drainPendingTargetSpecs(): List<PendingTarget> {
        val result = mutableListOf<PendingTarget>()
        while (true) {
            result.add(pendingTargetSpecs.poll() ?: break)
        }
        return result
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val HISTORY_CAP = 100
        private val log = LoggerFactory.getLogger(InteractivePromptBridge::class.java)
    }

    fun getTimeoutMs(): Long? = timeoutMs

    data class PendingPrompt(
        val promptId: String,
        val request: PromptRequest,
        val future: CompletableFuture<List<Int>>,
        /**
         * Live Forge SpellAbility for targeting prompts — session-only, never serialized.
         * Enables legality checks on remaining candidates during re-prompt building.
         * Null for non-targeting prompts.
         */
        val targetingSa: SpellAbility? = null,
    )

    // ── Call history ────────────────────────────────────────────────────────
    // Records every requestChoice invocation with its outcome. The engine
    // calls controller overrides that silently block on this bridge; without
    // a trace it's impossible to tell WHAT prompted and WHETHER it timed out.
    // Tests inspect `history` to diagnose unexpected blocking calls.

    enum class PromptCallStatus { RESPONDED, TIMEOUT, ERROR, ALREADY_PENDING, NON_GAME_THREAD }

    data class PromptRecord(
        val promptType: String,
        val message: String,
        val options: List<String>,
        val outcome: PromptCallStatus,
        val result: List<Int>,
        val callerFrames: List<String>,
    ) {
        override fun toString(): String =
            "[$outcome] $promptType: \"$message\" opts=$options result=$result\n  ${callerFrames.joinToString("\n  ")}"
    }

    private val _history = ArrayDeque<PromptRecord>(HISTORY_CAP)

    /** Immutable snapshot of recent prompt calls (oldest first, capped at [HISTORY_CAP]). */
    val history: List<PromptRecord> get() = synchronized(_history) { _history.toList() }

    private fun record(
        request: PromptRequest,
        outcome: PromptCallStatus,
        result: List<Int>,
        elapsedMs: Long,
    ) {
        val frames =
            Thread
                .currentThread()
                .stackTrace
                .drop(3) // skip getStackTrace, record, requestChoice
                .filter { it.className.startsWith("forge.") }
                .take(6)
                .map { "${it.className.substringAfterLast('.')}#${it.methodName}:${it.lineNumber}" }
        synchronized(_history) {
            if (_history.size >= HISTORY_CAP) _history.removeFirst()
            _history.addLast(PromptRecord(request.promptType, request.message, request.options, outcome, result, frames))
        }
        val secs = "%.1f".format(elapsedMs / 1000.0)
        val msg = "Prompt [${request.promptType}] \"${request.message}\" → $outcome $result (${secs}s)"
        when (outcome) {
            PromptCallStatus.RESPONDED -> log.info(msg)
            PromptCallStatus.TIMEOUT,
            PromptCallStatus.ERROR,
            PromptCallStatus.ALREADY_PENDING,
            PromptCallStatus.NON_GAME_THREAD,
            -> log.warn(msg)
        }
    }
    // ────────────────────────────────────────────────────────────────────────

    private val pending = AtomicReference<PendingPrompt?>(null)

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

    // ── Reveal tracking ─────────────────────────────────────────────────────
    // Engine fires reveal(); the leyline.bridge.forge.PlayerController override
    // pushes forge card IDs here. Leyline drains at diff-build time to produce
    // RevealedCardCreated annotations and populate Revealed zones.

    /**
     * Record of revealed cards: list of forge card IDs + the seatId of the player
     * who revealed them.
     */
    data class RevealRecord(
        val forgeCardIds: List<ForgeCardId>,
        val ownerSeatId: SeatId,
    )

    private val revealQueue = ConcurrentLinkedQueue<RevealRecord>()

    /** Push a batch of revealed card IDs (called from engine thread via the PlayerController.reveal override). */
    fun recordReveal(
        forgeCardIds: List<ForgeCardId>,
        ownerSeatId: SeatId,
    ) {
        if (forgeCardIds.isEmpty()) return
        revealQueue.add(RevealRecord(forgeCardIds, ownerSeatId))
        log.debug("Reveal recorded: {} cards for seat {}", forgeCardIds.size, ownerSeatId)
    }

    /** Clear all accumulated state for puzzle hot-swap. */
    fun resetForPuzzle() {
        synchronized(_history) { _history.clear() }
        revealQueue.clear()
        pendingTargetSpecs.clear()
        targetSpecIndexCounter.set(0)
        journal.resetForPuzzle()
        pending.set(null)
    }

    /** Drain all pending reveal records (called from annotation-build thread). */
    fun drainReveals(): List<RevealRecord> {
        val result = mutableListOf<RevealRecord>()
        while (true) {
            val record = revealQueue.poll() ?: break
            result.add(record)
        }
        return result
    }

    /**
     * Called from the engine thread (BLOCKS until client responds or timeout).
     *
     * @param request describes the prompt to show the client
     * @return list of selected indices into [request.options]
     */
    fun requestChoice(
        request: PromptRequest,
        targetingSa: SpellAbility? = null,
    ): List<Int> {
        if (!isGameLoopThread()) {
            val fallback = listOf(request.defaultIndex)
            log.warn(
                "Prompt [{}] \"{}\" requested from non-game thread {}, using default {}",
                request.promptType,
                request.message,
                Thread.currentThread().name,
                fallback,
            )
            record(request, PromptCallStatus.NON_GAME_THREAD, fallback, 0)
            return fallback
        }

        val configuredTimeoutMs = timeoutMs
        if (configuredTimeoutMs == 0L) {
            return listOf(request.defaultIndex)
        }

        val promptId = UUID.randomUUID().toString()
        val future = CompletableFuture<List<Int>>()
        val prompt = PendingPrompt(promptId, request, future, targetingSa)

        if (!pending.compareAndSet(null, prompt)) {
            val fallback = listOf(request.defaultIndex)
            record(request, PromptCallStatus.ALREADY_PENDING, fallback, 0)
            return fallback
        }
        prioritySignal?.signal()

        val startMs = System.currentTimeMillis()
        return try {
            val result =
                if (configuredTimeoutMs == null) {
                    future.get()
                } else {
                    future.get(configuredTimeoutMs, TimeUnit.MILLISECONDS)
                }
            record(request, PromptCallStatus.RESPONDED, result, System.currentTimeMillis() - startMs)
            prioritySignal?.markPromptResolved()
            result
        } catch (_: TimeoutException) {
            val diagnostic =
                BridgeTimeoutDiagnostic.buildMessage(
                    bridgeName = "InteractivePromptBridge",
                    timeoutMs = checkNotNull(configuredTimeoutMs),
                    game = diagnosticGame,
                    engineThread = diagnosticThread,
                    lastContext =
                        "Prompt(type=${request.promptType}, msg=\"${request.message}\", " +
                            "options=${request.options.size}, min=${request.min}, max=${request.max})",
                )
            log.warn("Prompt timed out, using default\n{}", diagnostic)
            DevCheck.failOnAutoPass { "Prompt timed out (type=${request.promptType}, msg=${request.message})" }
            val fallback = listOf(request.defaultIndex)
            record(request, PromptCallStatus.TIMEOUT, fallback, System.currentTimeMillis() - startMs)
            timeoutListener?.invoke()
            fallback
        } catch (ex: Exception) {
            log.error("Prompt failed with exception, using default", ex)
            DevCheck.failOnAutoPass { "Prompt failed: ${ex.message}" }
            val fallback = listOf(request.defaultIndex)
            record(request, PromptCallStatus.ERROR, fallback, System.currentTimeMillis() - startMs)
            fallback
        } finally {
            pending.set(null)
        }
    }

    private fun isGameLoopThread(): Boolean {
        val engineThread = diagnosticThread ?: return true
        return Thread.currentThread() == engineThread
    }

    /**
     * Called from the Netty handler. Completes the pending prompt future
     * so the blocked engine thread can resume.
     *
     * @return true if the prompt was matched and completed
     */
    fun submitResponse(
        promptId: String,
        selectedIndices: List<Int>,
    ): Boolean {
        val current = pending.get() ?: return false
        if (current.promptId != promptId) {
            log.warn("Prompt ID mismatch: expected=${current.promptId}, got=$promptId")
            return false
        }
        return current.future.complete(selectedIndices)
    }

    /**
     * Get the current pending prompt for client broadcast. Returns null if no prompt
     * is pending.
     */
    fun getPendingPrompt(): PendingPrompt? = pending.get()

    /**
     * Block until a prompt becomes pending (poll-based).
     * Replaces hand-rolled poll loops in tests.
     */
    fun awaitPendingPrompt(timeoutMs: Long = 5_000): PendingPrompt {
        var result: PendingPrompt? = null
        GameLoopPoller.awaitCondition(timeoutMs, pollIntervalMs = 20) {
            result = pending.get()
            result != null
        }
        return checkNotNull(result) { "No prompt within ${timeoutMs}ms" }
    }

    /**
     * Cancel any pending prompt (e.g. on game reset / disconnect).
     */
    fun cancelPending() {
        val current = pending.getAndSet(null)
        current?.future?.cancel(true)
    }
}

/**
 * Describes an interactive prompt the engine needs answered.
 */
enum class PromptSemantic {
    Generic,
    GroupingSurveil,
    GroupingScry,
    ModalChoice,
    SelectNLegendRule,
    SelectNDiscard,
    Search,

    /** "Choose from revealed hand" — Duress, Revealing Eye, Thoughtseize, etc. */
    RevealChoose,

    /**
     * "Pick K from a known visible list at resolution time" — Stock Up / Dig
     * effects, post-target resolution picks. Routes to `SelectNReq`
     * (Resolution context, Dynamic list) instead of falling through to
     * `SelectTargetsReq`.
     */
    SelectNResolution,

    /**
     * Sacrifice selection during effect resolution (edict-style prompts).
     * Routes to a regular `SelectNReq`, not the cost-payment envelope.
     */
    SelectNSacrificeEffect,

    /**
     * "Sacrifice N permanents" as a cost-payment selection. Routes to
     * `PayCostsReq` (NonManaPayment / Payment context); resolution sacrifice
     * effects should use [SelectNSacrificeEffect] instead.
     */
    SelectNCostSacrifice,

    /**
     * "Exile N cards from your graveyard" as an additional cost — Escape's
     * `K:Escape:<mana>|Type:Card|N:<n>` cost-payment selection. Routes to
     * `PayCostsReq` (NonManaPayment / Payment context) so the client renders
     * the cost-payment picker, parallel to the existing sacrifice cost path.
     */
    SelectNCostExileFromGrave,

    /**
     * Collect Evidence additional cost: choose any number of graveyard cards
     * whose total mana value meets the threshold. Routes to a weighted
     * `PayCostsReq` rather than the fixed-count Escape envelope.
     */
    SelectNCostCollectEvidence,

    /** Enlist's optional attack cost: a PayCostsReq, not target selection. */
    EnlistCost,

    /**
     * Station's tap-a-creature activation cost. Routes to the Station-specific
     * `PayCostsReq` promptId while keeping the normal non-mana payment envelope.
     */
    StationTapCost,

    /** Ninjutsu's "return an unblocked attacker" activation cost. */
    ReturnUnblockedAttackerCost,

    /** Mutate's resolution-time choice for which component is on top. */
    MutateTopBottom,

    /** Learn's Lesson/discard card picker. Routes to a Learn-specific `SelectNReq`. */
    LearnLesson,
}

data class PromptRequest(
    val promptType: String,
    val message: String,
    val options: List<String>,
    val min: Int = 1,
    val max: Int = 1,
    val defaultIndex: Int = 0,
    val semantic: PromptSemantic = PromptSemantic.Generic,
    val candidateRefs: List<PromptCandidateRefDto> = emptyList(),
    /** Per-candidate selection weights for weighted cost-payment prompts. */
    val costSelectionWeights: List<Int> = emptyList(),
    /** Minimum total selected weight for weighted cost-payment prompts. */
    val minSelectionWeight: Int? = null,
    /** Source card entity ID for targeting prompts (spell or ability source). */
    val sourceEntityId: Int? = null,
    /** Card name for modal ETB prompts — session layer resolves grpId from this. */
    val modalSourceCardName: String? = null,
    /** True when modal originates from a triggered ability (ETB), not spell-time. */
    val isTriggeredAbility: Boolean = false,
    /**
     * Forge `SpellAbility.id` for triggered modal prompts. Drives SA-id-keyed
     * surrogate iid resolution for the modal CTO request's `sourceInstanceId`,
     * matching the iid the StateMapper emits on the matching
     * AbilityInstanceCreated. Zero when the prompt isn't a triggered modal.
     */
    val forgeAbilityId: Int = 0,
    /**
     * All revealed cards (unfiltered) for reveal-choose prompts.
     * Maps to `unfilteredIds` in SelectNReq — shows the full hand even when
     * only a subset is selectable (e.g., noncreature nonland for Duress).
     */
    val unfilteredRefs: List<PromptCandidateRefDto> = emptyList(),
    /**
     * For each `options[i]`, its position in the unfiltered Choices list (the
     * ability's full mode list before Forge legality-filtering). When set,
     * `TargetingHandler.sendCastingTimeOptionsReq` uses these to index into
     * the card-DB childGrpIds — keeps the modal index space aligned with
     * `possible[]` when modes are pruned (e.g. Spree counter mode with no
     * stack target).
     */
    val modalChoicePossibleFullIndices: List<Int>? = null,
    /**
     * Per-`options` mode costs (`+ {cost}` portion only; base spell cost is
     * separate). One entry per mode in the same order as `options`. Empty
     * inner list = free mode (Charm). Non-empty = cost-bearing mode (Spree).
     *
     * When non-null, MUST have exactly `modalChoicePossibleFullIndices.size`
     * entries (== `options.size`); shorter lists silently drop costs from
     * later modes. Use empty inner list, not omission, for free modes.
     */
    val modalCosts: List<List<Pair<wotc.mtgo.gre.external.messaging.Messages.ManaColor, Int>>>? = null,
    /**
     * Full-list positions of modes Forge legality-filtered out (parallel to
     * `excludedModalCosts`). Resolves to `ModalReq.excludedOptions[]` on the
     * outbound CastingTimeOptionsReq — client renders them greyed-out so the
     * player sees what's not pickable.
     */
    val excludedModalFullIndices: List<Int>? = null,
    /**
     * Costs parallel to `excludedModalFullIndices`. Same shape and parallel-list
     * invariants as `modalCosts`.
     */
    val excludedModalCosts: List<List<Pair<wotc.mtgo.gre.external.messaging.Messages.ManaColor, Int>>>? = null,
)

/** Convert a pending engine prompt into its wire DTO. */
fun InteractivePromptBridge.PendingPrompt.toChoiceDto(): PromptChoiceDto {
    val req = request
    return PromptChoiceDto(
        promptId = promptId,
        promptType = req.promptType,
        message = req.message,
        min = req.min,
        max = req.max,
        options =
            req.options.mapIndexed { idx, label ->
                PromptOptionDto(id = idx.toString(), label = label)
            },
        candidateRefs = req.candidateRefs,
    )
}
