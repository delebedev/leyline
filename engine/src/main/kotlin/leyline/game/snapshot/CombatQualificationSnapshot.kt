package leyline.game.snapshot

import leyline.bridge.types.ForgeCardId
import leyline.game.codes.QualificationType

/** Protocol-neutral combat restriction projected at one engine cut. */
data class CombatQualificationSnapshot(
    val sourceCardId: ForgeCardId,
    val affectedCardId: ForgeCardId,
    val grpId: Int,
    val qualificationType: QualificationType,
    val cantBlockCardIds: Set<ForgeCardId> = emptySet(),
    val cantBeBlockedByCardIds: Set<ForgeCardId> = emptySet(),
)
