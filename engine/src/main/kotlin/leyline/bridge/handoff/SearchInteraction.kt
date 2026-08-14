package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId

/** Engine-thread source facts for a library-search interaction. */
data class SearchSourceValue(
    val hostCardId: ForgeCardId?,
    val forgeAbilityId: Int,
    val abilityOnStack: Boolean,
    val typeCycling: Boolean,
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
