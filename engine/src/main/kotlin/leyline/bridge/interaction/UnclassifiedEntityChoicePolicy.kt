package leyline.bridge.interaction

import leyline.DevCheck
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.ResolvedPromptRoute

/**
 * Disposition for a resolution choice outside a typed card route, such as a mixed
 * creature-and-player domain or a domain holding section headers or hidden cards.
 *
 * Generic entity choices without a typed route or named rule share this policy.
 * The choice is asked over the options the chooser can be shown. It is answered
 * without asking when the shown options leave nothing to choose.
 */
internal object UnclassifiedEntityChoicePolicy {
    sealed interface Disposition {
        /** Original option indices to ask over. */
        data class Ask(
            val shown: List<Int>,
        ) : Disposition

        /** Original option indices answered without asking. */
        data class Automatic(
            val indices: List<Int>,
        ) : Disposition
    }

    /**
     * @param showable per option, whether the chooser can be shown it
     * @param selectable per option, whether the engine accepts it as an answer;
     *   section headers are neither showable nor selectable
     */
    fun decide(
        request: PromptRequest,
        optional: Boolean,
        showable: List<Boolean>,
        selectable: List<Boolean>,
    ): Disposition? {
        val unclassified = request.route is ResolvedPromptRoute.UnclassifiedEntityChoice
        if (!unclassified && !isUnnamedGenericEntityChoice(request)) return null
        require(showable.size == request.options.size && selectable.size == request.options.size) {
            "Every option needs a showable and selectable flag"
        }
        val shown = request.options.indices.filter { showable[it] && selectable[it] }
        if (optional) return if (shown.isEmpty()) Disposition.Automatic(emptyList()) else Disposition.Ask(shown)
        val required = request.min.coerceAtLeast(1)
        if (shown.size > required) return Disposition.Ask(shown)
        // Every shown option is required. The rest cannot be told apart by the chooser,
        // so any legal pick among them is equivalent. An unclassified domain without a
        // shown option is a gap.
        if (shown.isEmpty() && unclassified) {
            DevCheck.fail { "Mandatory resolution choice has no option the chooser can be shown" }
        }
        val hidden = request.options.indices.filter { selectable[it] && it !in shown }
        return Disposition.Automatic(shown + hidden.take(required - shown.size))
    }

    /** A generic entity choice that no typed route or [leyline.bridge.handoff.AutomaticAnswerRule] covers. */
    private fun isUnnamedGenericEntityChoice(request: PromptRequest): Boolean {
        val route = request.route as? ResolvedPromptRoute.AutoResolve ?: return false
        return route.rule == null && request.candidateRefs.isEmpty() && request.targetingFinishOptionIndex == null
    }
}
