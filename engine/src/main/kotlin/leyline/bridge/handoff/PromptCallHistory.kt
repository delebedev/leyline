package leyline.bridge.handoff

enum class PromptCallStatus {
    RESPONDED,
    DEFAULTED_POLICY,
    TIMEOUT,
    ERROR,
    ALREADY_PENDING,
    NON_GAME_THREAD,
    NON_INTERACTIVE_SCOPE,
}

data class PromptRecord(
    val promptType: String,
    /** Exact route used by the pending prompt; diagnostics must not resolve it again. */
    val route: ResolvedPromptRoute,
    val message: String,
    val options: List<String>,
    val min: Int,
    val max: Int,
    val candidateCount: Int,
    val outcome: PromptCallStatus,
    val result: List<Int>,
    val callerFrames: List<String>,
    val costSelectionWeights: List<Int> = emptyList(),
    val minSelectionWeight: Int? = null,
) {
    val semantic: PromptSemantic get() = route.semantic

    override fun toString(): String =
        "[$outcome] $promptType/$semantic: \"$message\" opts=$options result=$result\n  ${callerFrames.joinToString("\n  ")}"
}
