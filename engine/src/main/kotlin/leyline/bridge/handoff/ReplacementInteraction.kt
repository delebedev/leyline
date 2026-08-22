package leyline.bridge.handoff

import forge.game.replacement.ReplacementEffect
import leyline.bridge.types.ForgeCardId

/**
 * Self-replacement keyword families supported by the V1 replacement route.
 *
 * Each entry maps to a keyword whose replacement effect lives on the affected
 * card itself (`Card.Self` semantics), so the affected object and the
 * conferring object are the same card and `conferringObjectZcid` stays omitted.
 */
enum class ReplacementKeywordKind {
    Madness,
}

/** One retained self-replacement option, holding its exact Forge handle. */
data class ReplacementOptionValue(
    val originalOptionIndex: Int,
    val hostForgeCardId: ForgeCardId,
    val keyword: ReplacementKeywordKind,
)

/** Immutable materialization input for one competing-replacement window. */
data class ReplacementWindowValue(
    val options: List<ReplacementOptionValue>,
    val defaultOptionIndex: Int,
) {
    init {
        require(options.size >= 2) { "Replacement selection requires at least two options" }
        require(options.distinctBy { it.hostForgeCardId }.size == options.size) {
            "Replacement options must be distinct self-replacements"
        }
    }
}

data class ReplacementInteractionResult(
    val optionIndex: Int,
    val handle: ReplacementEffect,
    val timedOut: Boolean = false,
)

class ReplacementInteractionTimeoutException : RuntimeException("Replacement interaction timed out")

/** Blocking engine-thread shell contract for competing self-replacement choices. */
interface ReplacementInteractionRuntime {
    /**
     * Returns null when the offered replacement effects do not satisfy the V1
     * self-replacement route, so the caller can fall back to inherited behavior.
     */
    fun awaitReplacement(
        request: PromptRequest,
        possibleReplacers: List<ReplacementEffect>,
        timeoutMs: Long?,
    ): ReplacementInteractionResult?
}

data class PublishedReplacementInteraction(
    val interactionId: String,
    val gameStateId: Int,
)
