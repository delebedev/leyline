package leyline.bridge.coord

import forge.game.card.Card
import forge.game.keyword.Keyword
import forge.game.replacement.ReplacementEffect
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.ReplacementOptionValue
import leyline.bridge.handoff.ReplacementWindowValue
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActivatedActionEmitter

/** Captures a projectable, keyword-backed replacement window on the engine thread. */
internal class ReplacementWindowCapture(
    private val owner: MatchCutCoordinator,
) {
    data class Initial(
        val value: ReplacementWindowValue,
        val handlesByOption: Map<Int, ReplacementEffect>,
    )

    @Suppress("ReturnCount")
    fun initial(
        request: PromptRequest,
        possibleReplacers: List<ReplacementEffect>,
    ): Initial? {
        if (request.route !is ResolvedPromptRoute.SelectReplacement) return null
        if (possibleReplacers.size < 2 || possibleReplacers.size != request.options.size) return null
        val options =
            possibleReplacers.mapIndexed { index, effect ->
                val host = effect.hostCard ?: return null
                if (!isSelfMadnessReplacement(effect, host)) return null
                val hostId = ForgeCardId(host.id)
                val instanceId = owner.bridge.peekInstanceId(hostId) ?: return null
                if (instanceId.value == 0) return null
                val grpId = owner.bridge.resolveGrpId(host)
                if (grpId == 0) return null
                val cardData = owner.bridge.cardRepository.findByGrpId(grpId) ?: return null
                val abilityGrpId = owner.bridge.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.MADNESS) ?: return null
                val uniqueAbilityId = ActivatedActionEmitter.uniqueAbilityIdFor(cardData, abilityGrpId) ?: return null
                ReplacementOptionValue(index, hostId, uniqueAbilityId, abilityGrpId)
            }
        if (options.map { it.hostForgeCardId }.distinct().size != options.size) return null
        val defaultIndex = request.defaultIndex.takeIf { it in options.indices } ?: return null
        return Initial(
            ReplacementWindowValue(options, defaultIndex),
            possibleReplacers.mapIndexed { index, effect -> index to effect }.toMap(),
        )
    }

    private fun isSelfMadnessReplacement(
        effect: ReplacementEffect,
        host: Card,
    ): Boolean {
        val keyword = host.keywords.firstOrNull { it.replacements.any { replacement -> replacement === effect } } ?: return false
        return keyword.keyword == Keyword.MADNESS
    }
}
