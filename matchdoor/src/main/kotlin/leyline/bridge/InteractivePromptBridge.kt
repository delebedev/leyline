package leyline.bridge

import forge.game.Game
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe bridge between the blocking engine thread and async WebSocket handlers.
 *
 * When the engine needs interactive input (choose cards, pick option, etc.),
 * [requestChoice] blocks the engine thread on a [CompletableFuture]. The WS handler
 * sends a prompt to the client, and when the client responds, [submitResponse]
 * completes the future so the engine resumes.
 *
 * One pending prompt at a time — the engine is single-threaded.
 */
class InteractivePromptBridge(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val prioritySignal: PrioritySignal? = null,
) {
    /**
     * Stashed optional cost decision (kicker, buyback, etc.).
     * Set by session layer (TargetingHandler) after client responds to CastingTimeOptionsReq.
     * Consumed by [WebPlayerController.chooseOptionalCosts]. Indices into OptionalCostValue list.
     * Null = no stash (auto-accept fallback). Empty list = decline all.
     */
    @Volatile
    var stashedOptionalCostIndices: List<Int>? = null

    /**
     * Forge card IDs of legendaries about to die to the legend rule SBA.
     *
     * Populated by [WebPlayerController.autoResolveLegendRule] before returning
     * (while still on the engine thread). [GameEventCollector] checks this set
     * during BF→GY zone transitions to emit [GameEvent.LegendRuleDeath] instead
     * of [GameEvent.CardDestroyed]. Thread-safe — WPC writes on engine thread,
     * collector reads on the same thread (events fire synchronously during SBA).
     */
    val legendRuleVictims: MutableSet<Int> = CopyOnWriteArraySet()

    /**
     * Forge card IDs of cards moved Library→Hand via a search effect (ChangeZone tutor).
     *
     * Populated by [WebPlayerController.chooseSingleEntityForEffect] when semantic=Search
     * and the chosen entity is a Card. [GameEventCollector] checks this set during
     * Library→Hand zone transitions to emit [GameEvent.CardSearchedToHand] instead of
     * [GameEvent.ZoneChanged], yielding [TransferCategory.Put] instead of [TransferCategory.Draw].
     * Thread-safe — WPC writes on engine thread; collector reads on the same thread.
     */
    val searchedToHandCards: MutableSet<Int> = CopyOnWriteArraySet()

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

    enum class PromptOutcome { RESPONDED, TIMEOUT, ERROR, ALREADY_PENDING }

    data class PromptRecord(
        val promptType: String,
        val message: String,
        val options: List<String>,
        val outcome: PromptOutcome,
        val result: List<Int>,
        val callerFrames: List<String>,
    ) {
        override fun toString(): String =
            "[$outcome] $promptType: \"$message\" opts=$options result=$result\n  ${callerFrames.joinToString("\n  ")}"
    }

    private val _history = ArrayDeque<PromptRecord>(HISTORY_CAP)

    /** Immutable snapshot of recent prompt calls (oldest first, capped at [HISTORY_CAP]). */
    val history: List<PromptRecord> get() = synchronized(_history) { _history.toList() }

    private fun record(request: PromptRequest, outcome: PromptOutcome, result: List<Int>, elapsedMs: Long) {
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
            PromptOutcome.RESPONDED -> log.info(msg)
            PromptOutcome.TIMEOUT, PromptOutcome.ERROR, PromptOutcome.ALREADY_PENDING -> log.warn(msg)
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

    /**
     * Set after a prompt resolves so the next priority check skips smart-phase-skip
     * and lets the player see the updated board. Cleared by [consumePromptResolved].
     */
    @Volatile
    var promptJustResolved: Boolean = false
        private set

    /** Check and clear the resolved flag (single consumer). */
    fun consumePromptResolved(): Boolean {
        if (!promptJustResolved) return false
        promptJustResolved = false
        return true
    }

    // ── Reveal tracking ─────────────────────────────────────────────────────
    // Engine calls PlayerController.reveal() → WebPlayerController.reveal()
    // pushes forge card IDs here. Leyline drains at diff-build time to
    // produce RevealedCardCreated annotations and populate Revealed zones.

    /**
     * Record of revealed cards: list of forge card IDs + the seatId of the player
     * who revealed them.
     */
    data class RevealRecord(val forgeCardIds: List<Int>, val ownerSeatId: Int)

    private val revealQueue = ConcurrentLinkedQueue<RevealRecord>()

    /** Push a batch of revealed card IDs (called from engine thread via WebPlayerController). */
    fun recordReveal(forgeCardIds: List<Int>, ownerSeatId: Int) {
        if (forgeCardIds.isEmpty()) return
        revealQueue.add(RevealRecord(forgeCardIds, ownerSeatId))
        log.debug("Reveal recorded: {} cards for seat {}", forgeCardIds.size, ownerSeatId)
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
            record(request, PromptOutcome.ALREADY_PENDING, fallback, 0)
            return fallback
        }
        prioritySignal?.signal()

        val startMs = System.currentTimeMillis()
        return try {
            val result = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            record(request, PromptOutcome.RESPONDED, result, System.currentTimeMillis() - startMs)
            promptJustResolved = true
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
            val fallback = listOf(request.defaultIndex)
            record(request, PromptOutcome.TIMEOUT, fallback, System.currentTimeMillis() - startMs)
            fallback
        } catch (ex: Exception) {
            log.error("Prompt failed with exception, using default", ex)
            val fallback = listOf(request.defaultIndex)
            record(request, PromptOutcome.ERROR, fallback, System.currentTimeMillis() - startMs)
            fallback
        } finally {
            pending.set(null)
        }
    }

    /**
     * Called from the WS handler coroutine. Completes the pending prompt future
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
     * Get the current pending prompt for WS broadcast. Returns null if no prompt
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
