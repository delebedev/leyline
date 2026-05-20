package leyline.game.mapping

import forge.game.Game
import leyline.bridge.findCard
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.data.KeywordAbilityIds
import leyline.game.state.GameBridge

/**
 * Builds the resolver: `(cardInstanceId, staticId) → sourceAbilityGRPID`.
 *
 * Two resolution paths:
 * - **staticId > 0**: continuous effect from a StaticAbility — use [leyline.game.state.AbilityRegistry]
 *   to look up the specific ability. Falls back to keyword parent tracing for
 *   non-intrinsic temporaries.
 * - **staticId == 0**: resolved spell/trigger pump (e.g. Prowess) — falls back to
 *   the card's keyword-ability grpId (via [leyline.game.data.CardRepository.findKeywordAbilityGrpId])
 *   since Forge doesn't tag these with a source ability ID.
 *
 * Lives outside the `NoGameInMappers` denied set; legitimately reads
 * [forge.game.Game] via [bridge] (depends on live Forge card state for
 * `card.staticAbilities` lookups).
 */
object SourceAbilityResolverFactory {
    /** Keyword ability ids whose triggered/resolved effects produce P/T boosts with staticId=0. */
    private val PT_BOOST_KEYWORDS = setOf(KeywordAbilityIds.PROWESS, KeywordAbilityIds.ENLIST)

    fun build(bridge: GameBridge): (InstanceId, Long) -> GrpId? {
        val game: Game = bridge.getGame() ?: return { _, _ -> null }
        return resolver@{ instanceId, staticId ->
            val cardId = bridge.getForgeCardId(instanceId) ?: return@resolver null
            val card = findCard(game, cardId) ?: return@resolver null
            val grpId = bridge.cardRepository.findGrpIdByName(card.name) ?: return@resolver null
            val cardData = bridge.cardRepository.findByGrpId(grpId) ?: return@resolver null

            // Resolved pump effects (Prowess, Giant Growth): staticId = 0
            if (staticId == 0L) {
                for (keywordId in PT_BOOST_KEYWORDS) {
                    bridge.cardRepository.findKeywordAbilityGrpId(grpId, keywordId)?.let { return@resolver GrpId(it) }
                }
                return@resolver null
            }

            if (staticId > Int.MAX_VALUE) return@resolver null

            // Continuous effects: use AbilityRegistry for precise lookup
            val registry = bridge.abilityRegistryFor(card, cardData) ?: return@resolver null
            registry.forStaticAbility(staticId.toInt())?.let { return@resolver GrpId(it) }

            // Keyword fallback: temporary statics from keyword triggers
            val sourceStatic = card.staticAbilities?.firstOrNull { it.id == staticId.toInt() }
            val parentKeyword = sourceStatic?.keyword ?: return@resolver null
            for (sa in parentKeyword.abilities) {
                registry.forSpellAbility(sa.id)?.let { return@resolver GrpId(it) }
            }
            for (trig in parentKeyword.triggers) {
                registry.forTrigger(trig.id)?.let { return@resolver GrpId(it) }
            }
            for (st in parentKeyword.staticAbilities) {
                registry.forStaticAbility(st.id)?.let { return@resolver GrpId(it) }
            }
            null
        }
    }
}
