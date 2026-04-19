package leyline.bridge

import forge.game.Game
import leyline.DevCheck
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val prioritySignal: PrioritySignal? = null,
) {
    /**
     * Typed per-seat journal of prompt side-effects. Coordinators record
     * [PromptSideEffect] entries on the engine thread; consumers
     * ([GameEventCollector], [StateMapper], [CostPaymentCoordinator]) drain
     * them during GSM assembly.
     */
    val journal: PromptJournal = PromptJournal()

    // --- Pending TargetSpec data (captured during selectTargetsInteractively) ---

    /** Captured target: spell card ID + name, target card ID, 1-based group index. */
    data class PendingTarget(val spellForgeCardId: Int, val spellName: String, val targetForgeCardId: Int, val index: Int)

    private val pendingTargetSpecs = java.util.concurrent.ConcurrentLinkedQueue<PendingTarget>()
    private val targetSpecIndexCounter = java.util.concurrent.atomic.AtomicInteger(0)

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

    data class PendingPrompt(
        val promptId: String,
        val request: PromptRequest,
        val future: CompletableFuture<List<Int>>,
    )

    // ── Call history ────────────────────────────────────────────────────────
    // Records every requestChoice invocation with its outcome. The engine
    // calls controller overrides that silently block on this bridge; without
    // a trace it's impossible to tell WHAT prompted and WHETHER it timed out.
    // Tests inspect `history` to diagnose unexpected blocking calls.

    enum class PromptCallStatus { RESPONDED, TIMEOUT, ERROR, ALREADY_PENDING }

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

    private fun record(request: PromptRequest, outcome: PromptCallStatus, result: List<Int>, elapsedMs: Long) {
        val frames = Thread.currentThread().stackTrace
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
            PromptCallStatus.TIMEOUT, PromptCallStatus.ERROR, PromptCallStatus.ALREADY_PENDING -> log.warn(msg)
        }
    }
    // ────────────────────────────────────────────────────────────────────────

    private val pending = AtomicReference<PendingPrompt?>(null)

    // -- Diagnostic context (set by GameLoopController after thread launch) --

    @Volatile private var diagnosticGame: Game? = null

    @Volatile private var diagnosticThread: Thread? = null

    /** Set diagnostic context for timeout messages. Called by [GameLoopController]. */
    fun setDiagnosticContext(game: Game, engineThread: Thread) {
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
    data class RevealRecord(val forgeCardIds: List<ForgeCardId>, val ownerSeatId: SeatId)

    private val revealQueue = ConcurrentLinkedQueue<RevealRecord>()

    /** Push a batch of revealed card IDs (called from engine thread via the PlayerController.reveal override). */
    fun recordReveal(forgeCardIds: List<ForgeCardId>, ownerSeatId: SeatId) {
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
    fun requestChoice(request: PromptRequest): List<Int> {
        if (timeoutMs <= 0L) {
            return listOf(request.defaultIndex)
        }

        val promptId = UUID.randomUUID().toString()
        val future = CompletableFuture<List<Int>>()
        val prompt = PendingPrompt(promptId, request, future)

        if (!pending.compareAndSet(null, prompt)) {
            val fallback = listOf(request.defaultIndex)
            record(request, PromptCallStatus.ALREADY_PENDING, fallback, 0)
            return fallback
        }
        prioritySignal?.signal()

        val startMs = System.currentTimeMillis()
        return try {
            val result = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            record(request, PromptCallStatus.RESPONDED, result, System.currentTimeMillis() - startMs)
            prioritySignal?.markPromptResolved()
            result
        } catch (_: TimeoutException) {
            val diagnostic = BridgeTimeoutDiagnostic.buildMessage(
                bridgeName = "InteractivePromptBridge",
                timeoutMs = timeoutMs,
                game = diagnosticGame,
                engineThread = diagnosticThread,
                lastContext = "Prompt(type=${request.promptType}, msg=\"${request.message}\", " +
                    "options=${request.options.size}, min=${request.min}, max=${request.max})",
            )
            log.warn("Prompt timed out, using default\n{}", diagnostic)
            DevCheck.failOnAutoPass { "Prompt timed out (type=${request.promptType}, msg=${request.message})" }
            val fallback = listOf(request.defaultIndex)
            record(request, PromptCallStatus.TIMEOUT, fallback, System.currentTimeMillis() - startMs)
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

    /**
     * Called from the Netty handler. Completes the pending prompt future
     * so the blocked engine thread can resume.
     *
     * @return true if the prompt was matched and completed
     */
    fun submitResponse(promptId: String, selectedIndices: List<Int>): Boolean {
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
    /** Source card entity ID for targeting prompts (spell or ability source). */
    val sourceEntityId: Int? = null,
    /** Card name for modal ETB prompts — session layer resolves grpId from this. */
    val modalSourceCardName: String? = null,
    /** True when modal originates from a triggered ability (ETB), not spell-time. */
    val isTriggeredAbility: Boolean = false,
    /**
     * All revealed cards (unfiltered) for reveal-choose prompts.
     * Maps to `unfilteredIds` in SelectNReq — shows the full hand even when
     * only a subset is selectable (e.g., noncreature nonland for Duress).
     */
    val unfilteredRefs: List<PromptCandidateRefDto> = emptyList(),
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
        options = req.options.mapIndexed { idx, label ->
            PromptOptionDto(id = idx.toString(), label = label)
        },
        candidateRefs = req.candidateRefs,
    )
}
