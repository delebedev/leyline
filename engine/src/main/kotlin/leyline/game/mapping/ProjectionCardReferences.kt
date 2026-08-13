package leyline.game.mapping

import leyline.game.data.CardData
import leyline.game.data.CardRepository
import leyline.game.data.KeywordAbilityIds

/** Narrow read-only card metadata used by state projection. */
class ProjectionCardReferences internal constructor(
    private val cards: CardRepository,
) {
    fun choiceSourceAbilityGrpId(data: CardData?): Int? =
        data
            ?.abilityIds
            ?.firstOrNull { (abilityGrpId, _) -> cards.findAbilityInfo(abilityGrpId)?.category == STATIC_ABILITY_CATEGORY }
            ?.first

    fun cardDataByName(cardName: String): CardData? = cards.findGrpIdByName(cardName)?.let(cards::findByGrpId)

    fun hasTraining(grpId: Int): Boolean = cards.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.TRAINING) != null

    fun isBackupAbility(
        sourceGrpId: Int,
        abilityGrpId: Int,
    ): Boolean = cards.findKeywordAbilityGrpId(sourceGrpId, KeywordAbilityIds.BACKUP) == abilityGrpId

    fun targetSpecAbilityGrpId(cardName: String): Int {
        val cardGrpId = cards.findGrpIdByName(cardName) ?: return 0
        val card = cards.findByGrpId(cardGrpId) ?: return cardGrpId
        return card.abilityIds
            .firstOrNull { (abilityGrpId, _) -> cards.findAbilityInfo(abilityGrpId)?.category == SPELL_EFFECT_CATEGORY }
            ?.first
            ?: card.abilityIds.firstOrNull()?.first
            ?: cardGrpId
    }

    fun collectEvidenceAbilityGrpId(cardName: String): Int {
        val grpId = cards.findGrpIdByName(cardName) ?: return 0
        val data = cards.findByGrpId(grpId) ?: return 0
        return data.abilityIds
            .firstOrNull { (abilityGrpId, _) ->
                val info = cards.findAbilityInfo(abilityGrpId)
                info?.category == COLLECT_EVIDENCE_CATEGORY && info.subCategory == COLLECT_EVIDENCE_SUBCATEGORY
            }?.first ?: 0
    }

    fun decayedCleanupGrpId(grpId: Int): Int? {
        if (cards.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.DECAYED) == null) return null
        return cards.findHiddenTriggeredAbilityGrpId(grpId)
    }

    private companion object {
        const val STATIC_ABILITY_CATEGORY = 3
        const val COLLECT_EVIDENCE_CATEGORY = 5
        const val COLLECT_EVIDENCE_SUBCATEGORY = 29
        const val SPELL_EFFECT_CATEGORY = 4
    }
}
