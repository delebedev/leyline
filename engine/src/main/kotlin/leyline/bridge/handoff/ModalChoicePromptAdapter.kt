package leyline.bridge.handoff

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
 * request; [askByLabel] asks it as a named label choice over the option labels instead.
 */
internal class ModalChoicePromptAdapter(
    private val timeoutMs: Long?,
    private val strict: Boolean,
    private val isGameLoopThread: () -> Boolean,
    private val runtime: () -> ModalChoiceInteractionRuntime?,
    private val prioritySignal: PrioritySignal?,
    private val record: (PromptRequest, PromptCallStatus, List<Int>) -> Unit,
    private val askByLabel: (PromptRequest) -> List<Int>,
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
        // A named label choice selects distinct labels, so a repeatable multi-pick keeps the modal route.
        if ((!request.allowRepeat || request.max <= 1) && !modalRuntime.identifiesModes(possible, sourceCard, sourceAbility)) {
            return askByLabel(request.asNamedChoice()).mapNotNull(possible::getOrNull)
        }
        val result =
            try {
                modalRuntime.awaitSelection(request, possible, sourceCard, sourceAbility, timeoutMs)
            } catch (ex: Exception) {
                record(request, PromptCallStatus.ERROR, emptyList())
                throw ex
            }
        record(
            request,
            if (result.timedOut) PromptCallStatus.TIMEOUT else PromptCallStatus.RESPONDED,
            result.optionIndices,
        )
        if (result.timedOut) prioritySignal?.signal() else prioritySignal?.markPromptResolved()
        if (result.cancelled && !sourceAbility.isTrigger) throw ModalCastCancelledException()
        return result.handles
    }

    private fun PromptRequest.asNamedChoice(): PromptRequest =
        copy(
            max = max.coerceAtMost(options.size),
            allowRepeat = false,
            route = PromptRouteResolver.resolve(PromptSemantic.NamedChoice),
            staticList = null,
            staticOptionIds = options.indices.map { it + 1 },
        )
}
