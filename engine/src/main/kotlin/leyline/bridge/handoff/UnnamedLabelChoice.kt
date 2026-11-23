package leyline.bridge.handoff

/**
 * Plans a generic label request that no typed route or [AutomaticAnswerRule] covers.
 * It is asked as a named choice over the labels that are not section headers, and
 * answered under [AutomaticAnswerRule.NoChoiceAmongShownOptions] when one shown label or
 * none remains.
 */
internal object UnnamedLabelChoice {
    sealed interface Plan {
        /** Original option indices answered without asking. */
        data class Automatic(
            val indices: List<Int>,
        ) : Plan

        /** [request] asks over [shown], the original indices of its options. */
        data class Ask(
            val request: PromptRequest,
            val shown: List<Int>,
        ) : Plan
    }

    fun plan(request: PromptRequest): Plan? {
        val route = request.route as? ResolvedPromptRoute.AutoResolve ?: return null
        if (route.rule != null || request.targetingFinishOptionIndex != null || request.candidateRefs.isNotEmpty()) return null
        val shown = request.options.indices.filterNot { request.options[it].isSectionHeaderLabel() }
        val min = request.min.coerceIn(0, shown.size)
        if (shown.size < 2 && !(min == 0 && shown.size == 1)) {
            return Plan.Automatic(if (min == 0) emptyList() else shown.ifEmpty { listOf(request.defaultIndex) })
        }
        val labels =
            request.copy(
                options = shown.map(request.options::get),
                min = min,
                max = request.max.coerceIn(maxOf(min, 1), shown.size),
                defaultIndex = shown.indexOf(request.defaultIndex).coerceAtLeast(0),
                route = PromptRouteResolver.resolve(PromptSemantic.NamedChoice),
                staticList = null,
                staticOptionIds = shown.indices.map { it + 1 },
            )
        return Plan.Ask(labels, shown)
    }

    /** Forge's "--CARDS IN ...:--" captions group options and cannot be chosen. */
    private fun String.isSectionHeaderLabel(): Boolean = startsWith("--") && endsWith("--")
}
