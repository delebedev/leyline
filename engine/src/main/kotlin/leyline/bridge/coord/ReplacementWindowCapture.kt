package leyline.bridge.coord

import forge.game.card.Card
import forge.game.keyword.Keyword
import forge.game.replacement.ReplacementEffect
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.ReplacementKeywordKind
import leyline.bridge.handoff.ReplacementOptionValue
import leyline.bridge.handoff.ReplacementWindowValue
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId

/**
 * Engine-thread capture for one immutable competing-replacement window.
 *
 * V1 supports only distinct self-replacements: every [ReplacementEffect] must
 * be keyword-backed on its own host card (so the affected object is the card
 * itself, never a third-party conferring object) and the keyword must map to a
 * known self-replacement family. Anything else returns null so the caller can
 * preserve the inherited controller fallback instead of fabricating identity.
 */
internal object ReplacementWindowCapture {
    data class Initial(
        val value: ReplacementWindowValue,
        val handlesByOption: Map<Int, ReplacementEffect>,
    )

    fun initial(
        request: PromptRequest,
        possibleReplacers: List<ReplacementEffect>,
    ): Initial? {
        check(request.route is ResolvedPromptRoute.SelectReplacement) { "SelectReplacement route required" }
        check(possibleReplacers.size == request.options.size) { "Replacement handles must match options" }
        check(possibleReplacers.size >= 2) { "Replacement selection requires at least two options" }
        val options =
            possibleReplacers.mapIndexed { index, effect ->
                val host = effect.hostCard ?: return null
                val keywordKind = selfReplacementKeyword(effect, host) ?: return null
                ReplacementOptionValue(index, ForgeCardId(host.id), keywordKind)
            }
        if (options.distinctBy { it.hostForgeCardId }.size != options.size) return null
        return Initial(
            value =
                ReplacementWindowValue(
                    options = options,
                    defaultOptionIndex = request.defaultIndex.coerceIn(0, options.lastIndex),
                ),
            handlesByOption = possibleReplacers.mapIndexed { index, effect -> index to effect }.toMap(),
        )
    }

    /** Resolve a keyword-backed self-replacement to its V1 family, or null when unsupported. */
    private fun selfReplacementKeyword(
        effect: ReplacementEffect,
        host: Card,
    ): ReplacementKeywordKind? {
        val keyword =
            host.keywords.firstOrNull { kw -> kw.replacements.any { it === effect } }
                ?: return null
        return when (keyword.keyword) {
            Keyword.MADNESS -> ReplacementKeywordKind.Madness
            else -> null
        }
    }
}
