package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId

/** Engine-thread source facts for a library-search interaction. */
data class SearchSourceValue(
    val hostCardId: ForgeCardId?,
    val forgeAbilityId: Int,
    val abilityOnStack: Boolean,
    val typeCycling: Boolean,
)

/** One disjoint candidate partition for a grouped library search. */
data class SearchGroupValue(
    val groupId: Int,
    val candidateCardIdsByOption: Map<Int, ForgeCardId>,
    val maxSelect: Int = 1,
)

/** Immutable input for one coordinator-owned library-search window. */
data class SearchWindowValue(
    val libraryCardIds: List<ForgeCardId>,
    val candidateCardIdsByOption: Map<Int, ForgeCardId>,
    val optionCount: Int,
    val minFind: Int,
    val maxFind: Int,
    val defaultIndex: Int,
    val source: SearchSourceValue?,
    val groups: List<SearchGroupValue> = emptyList(),
) {
    init {
        require(groups.map { it.groupId }.distinct().size == groups.size) { "Search group ids must be unique" }
        val groupedOptions = groups.flatMap { it.candidateCardIdsByOption.keys }
        require(groupedOptions.distinct().size == groupedOptions.size) { "Search candidates must belong to one group" }
        require(groupedOptions.all(candidateCardIdsByOption::containsKey)) { "Search groups must reference candidate options" }
        require(groups.isEmpty() || groupedOptions.toSet() == candidateCardIdsByOption.keys) {
            "Grouped search must partition every candidate option"
        }
    }
}

/** Value-only response row echoed by SearchFromGroupsResp. */
data class SearchGroupResponseValue(
    val groupId: Int,
    val selectedInstanceIds: List<Int>,
    val maxSelect: Int,
)

data class PublishedSearchInteraction(
    val interactionId: String,
    val gameStateId: Int,
)

class SearchInteractionTimeoutException : RuntimeException("Search interaction timed out")

/** Blocking engine-thread contract for the migrated Search route. */
interface SearchInteractionRuntime {
    fun awaitSearch(
        request: PromptRequest,
        timeoutMs: Long?,
    ): List<Int>
}
