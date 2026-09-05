package leyline.game.snapshot

import leyline.game.data.KeywordAbilityIds

/** Client visual bundle supported for a delayed-trigger source. */
internal data class PendingTriggerVisualPolicy(
    val cleanupAbilityGrpId: Int,
    val displaysAffectedCards: Boolean,
    val holderUsesSourceCardGrpId: Boolean = false,
    val removesFromZone: Int? = null,
    val emitsTemporaryPermanent: Boolean = true,
) {
    companion object {
        val warp =
            PendingTriggerVisualPolicy(
                cleanupAbilityGrpId = KeywordAbilityIds.WARP_DELAYED_TRIGGER,
                displaysAffectedCards = false,
                holderUsesSourceCardGrpId = true,
                removesFromZone = 1,
                emitsTemporaryPermanent = false,
            )

        private val bySourceCardGrpId =
            mapOf(
                70_155 to PendingTriggerVisualPolicy(136_220, displaysAffectedCards = true),
                93_996 to PendingTriggerVisualPolicy(136_220, displaysAffectedCards = true),
                102_473 to PendingTriggerVisualPolicy(204_550, displaysAffectedCards = true),
                104_978 to PendingTriggerVisualPolicy(206_386, displaysAffectedCards = true),
                93_779 to PendingTriggerVisualPolicy(179_839, displaysAffectedCards = false),
            )

        fun forSourceCard(cardGrpId: Int): PendingTriggerVisualPolicy? = bySourceCardGrpId[cardGrpId]
    }
}
