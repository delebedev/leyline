package leyline.bridge.handoff

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.spellability.SpellAbility
import leyline.bridge.NonInteractiveScope
import leyline.bridge.types.PrioritySignal

/**
 * Thin engine/session handoff for exact-handle modal requests.
 *
 * A choice whose options the client card data does not identify as modes, such as a
 * repeated [forge.game.ability.ApiType.GenericChoice], cannot be built as a modal
 * request; it is asked through [labelRuntime] as a named label choice over the option
 * labels instead.
 */
internal class ModalChoicePromptAdapter(
    private val timeoutMs: Long?,
    private val strict: Boolean,
    private val isGameLoopThread: () -> Boolean,
    private val runtime: () -> ModalChoiceInteractionRuntime?,
    private val prioritySignal: PrioritySignal?,
    private val record: (PromptRequest, PromptCallStatus, List<Int>) -> Unit,
    private val labelRuntime: () -> StaticChoiceInteractionRuntime?,
) {
    fun request(
        request: PromptRequest,
        possible: List<AbilitySub>,
        sourceCard: Card,
        sourceAbility: SpellAbility,
    ): List<AbilitySub> {
        resolvePromptPolicyDefault(request) { indices ->
            record(request, PromptCallStatus.DEFAULTED_POLICY, indices)
            prioritySignal?.markPromptResolved()
        }?.let { indices -> return indices.mapNotNull(possible::getOrNull) }
        val fallback =
            if (request.route.semantic == PromptSemantic.VoteChoice && request.min == 0) emptyList() else listOf(request.defaultIndex)
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
            record(request, PromptCallStatus.NON_INTERACTIVE_SCOPE, fallback)
            return fallback.mapNotNull(possible::getOrNull)
        }
        if (!isGameLoopThread()) {
            record(request, PromptCallStatus.NON_GAME_THREAD, fallback)
            return fallback.mapNotNull(possible::getOrNull)
        }
        if (timeoutMs == 0L) {
            return fallback.mapNotNull(possible::getOrNull)
        }
        val modalRuntime = checkNotNull(runtime()) { "ModalChoice runtime is not registered" }
        val result =
            try {
                if (modalRuntime.identifiesModes(possible, sourceCard, sourceAbility)) {
                    modalRuntime.awaitSelection(request, possible, sourceCard, sourceAbility, timeoutMs)
                } else {
                    null
                }
            } catch (ex: Exception) {
                record(request, PromptCallStatus.ERROR, emptyList())
                throw ex
            }
        if (result == null) return requestByLabel(request, fallback, sourceAbility).mapNotNull(possible::getOrNull)
        record(
            request,
            if (result.timedOut) PromptCallStatus.TIMEOUT else PromptCallStatus.RESPONDED,
            result.optionIndices,
        )
        if (result.timedOut) prioritySignal?.signal() else prioritySignal?.markPromptResolved()
        if (result.cancelled && !sourceAbility.isTrigger) throw ModalCastCancelledException()
        return result.handles
    }

    /**
     * Asks [request] by label. A repeatable choice is asked one pick at a time, each pick
     * required until [PromptRequest.min] picks are made and optional up to
     * [PromptRequest.max]. Only a cast-time choice may be cancelled, as on the modal route.
     */
    private fun requestByLabel(
        request: PromptRequest,
        fallback: List<Int>,
        sourceAbility: SpellAbility,
    ): List<Int> {
        val cancellable =
            request.route.semantic == PromptSemantic.ModalChoice &&
                !sourceAbility.isTrigger &&
                sourceAbility.api != ApiType.GenericChoice &&
                sourceAbility.api != ApiType.VillainousChoice
        if (!request.allowRepeat || request.max <= 1) {
            return askByLabel(request.asNamedChoice(request.min, request.max, cancellable), fallback)
        }
        val picks = mutableListOf<Int>()
        while (picks.size < request.max) {
            val required = if (picks.size < request.min) 1 else 0
            val pick = askByLabel(request.asNamedChoice(required, 1, cancellable), fallback.take(required))
            if (pick.isEmpty()) break
            picks += pick
        }
        return picks
    }

    /** Asks one label choice; a timeout answers [timeoutAnswer] and a cancellation unwinds the cast. */
    private fun askByLabel(
        request: PromptRequest,
        timeoutAnswer: List<Int>,
    ): List<Int> {
        val labels = checkNotNull(labelRuntime()) { "StaticChoice runtime is not registered" }
        return try {
            labels.awaitSelection(request, timeoutMs).also {
                record(request, PromptCallStatus.RESPONDED, it)
                prioritySignal?.markPromptResolved()
            }
        } catch (_: StaticChoiceInteractionTimeoutException) {
            record(request, PromptCallStatus.TIMEOUT, timeoutAnswer)
            prioritySignal?.signal()
            timeoutAnswer
        } catch (_: StaticChoiceCancelledException) {
            record(request, PromptCallStatus.RESPONDED, emptyList())
            prioritySignal?.markPromptResolved()
            throw ModalCastCancelledException()
        } catch (ex: Exception) {
            record(request, PromptCallStatus.ERROR, emptyList())
            throw ex
        }
    }

    private fun PromptRequest.asNamedChoice(
        min: Int,
        max: Int,
        cancellable: Boolean,
    ): PromptRequest {
        val cappedMin = min.coerceIn(0, options.size)
        return copy(
            min = cappedMin,
            max = max.coerceIn(maxOf(cappedMin, 1), options.size),
            allowRepeat = false,
            defaultIndex = defaultIndex.coerceIn(options.indices),
            route = PromptRouteResolver.resolve(PromptSemantic.NamedChoice),
            staticList = null,
            staticOptionIds = options.indices.map { it + 1 },
            allowCancel = cancellable,
        )
    }
}
