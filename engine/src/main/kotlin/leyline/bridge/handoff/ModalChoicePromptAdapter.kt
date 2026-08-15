package leyline.bridge.handoff

import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.spellability.SpellAbility
import leyline.bridge.NonInteractiveScope
import leyline.bridge.types.PrioritySignal
import org.slf4j.LoggerFactory

/** Thin engine/session handoff for exact-handle modal requests. */
internal class ModalChoicePromptAdapter(
    private val timeoutMs: Long?,
    private val strict: Boolean,
    private val isGameLoopThread: () -> Boolean,
    private val runtime: () -> ModalChoiceInteractionRuntime?,
    private val prioritySignal: PrioritySignal?,
    private val timeoutListener: (() -> Unit)?,
    private val record: (PromptRequest, PromptCallStatus, List<Int>, Long) -> Unit,
) {
    private val log = LoggerFactory.getLogger(ModalChoicePromptAdapter::class.java)

    fun request(
        request: PromptRequest,
        possible: List<AbilitySub>,
        sourceCard: Card,
        sourceAbility: SpellAbility,
    ): List<AbilitySub> {
        resolvePromptPolicyDefault(request, log) { indices ->
            record(request, PromptCallStatus.DEFAULTED_POLICY, indices, 0)
            prioritySignal?.markPromptResolved()
        }?.let { indices -> return indices.mapNotNull(possible::getOrNull) }
        val scope = NonInteractiveScope.active
        if (scope != null && strict) {
            refuseStrictPrompt(
                "[strict] Prompt [${request.promptType}] \"${request.message}\" requested inside non-interactive scope $scope",
            )
        }
        if (!isGameLoopThread() && strict) {
            refuseStrictPrompt(
                "[strict] Prompt [${request.promptType}] \"${request.message}\" requested " +
                    "from non-game thread ${Thread.currentThread().name}",
            )
        }
        if (scope != null) {
            val fallback = listOf(request.defaultIndex)
            log.warn(
                "Prompt [{}] \"{}\" requested inside non-interactive scope {}, using default {}",
                request.promptType,
                request.message,
                scope,
                fallback,
            )
            record(request, PromptCallStatus.NON_INTERACTIVE_SCOPE, fallback, 0)
            return fallback.mapNotNull(possible::getOrNull)
        }
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
            return fallback.mapNotNull(possible::getOrNull)
        }
        if (timeoutMs == 0L) {
            val fallback = listOf(request.defaultIndex)
            return fallback.mapNotNull(possible::getOrNull)
        }
        val modalRuntime = checkNotNull(runtime()) { "ModalChoice runtime is not registered" }
        val startMs = System.currentTimeMillis()
        return try {
            val result = modalRuntime.awaitSelection(request, possible, sourceCard, sourceAbility, timeoutMs)
            record(
                request,
                if (result.timedOut) PromptCallStatus.TIMEOUT else PromptCallStatus.RESPONDED,
                result.optionIndices,
                System.currentTimeMillis() - startMs,
            )
            if (result.timedOut) timeoutListener?.invoke() else prioritySignal?.markPromptResolved()
            result.handles
        } catch (ex: Exception) {
            record(request, PromptCallStatus.ERROR, emptyList(), System.currentTimeMillis() - startMs)
            throw ex
        }
    }
}
