package leyline.game

import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * Stage 2 of the annotation pipeline: generate annotations for zone transfers.
 *
 * Pure function — no bridge access, no side effects. Independently testable.
 * Extracted from [AnnotationPipeline] for independent maintainability.
 */
object TransferAnnotations {

    /**
     * ManaPaid.id base value. The real server assigns mana payment IDs sequentially
     * across the GSM. CastSpell payments typically start at id=3 (after prior
     * persistent annotation IDs 1-2). Best-effort approximation — a proper fix
     * would track a global counter across the GSM.
     */
    private const val MANA_ID_BASE = 3

    /**
     * Generate annotations for a single zone transfer.
     * **Pure function** — no bridge access, no side effects. Independently testable.
     *
     * Returns (transient annotations, persistent annotations).
     */
    fun annotationsForTransfer(
        transfer: AppliedTransfer,
        actingSeat: Int,
    ): Pair<List<AnnotationInfo>, List<AnnotationInfo>> {
        val origId = transfer.origId
        val newId = transfer.newId
        val category = transfer.category
        val srcZone = transfer.srcZoneId
        val destZone = transfer.destZoneId
        val grpId = transfer.grpId
        val affectorId = transfer.affectorId
        val annotations = mutableListOf<AnnotationInfo>()
        val persistent = mutableListOf<AnnotationInfo>()

        when (category) {
            TransferCategory.PlayLand -> {
                annotations.add(AnnotationBuilder.objectIdChanged(origId, newId))
                annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label))
                annotations.add(AnnotationBuilder.userActionTaken(newId, actingSeat, actionType = 3))
            }
            TransferCategory.CastSpell -> {
                annotations.add(AnnotationBuilder.objectIdChanged(origId, newId))
                annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label))
                // Per-land mana payment block (repeats for each land tapped)
                for ((i, mp) in transfer.manaPayments.withIndex()) {
                    annotations.add(
                        AnnotationBuilder.abilityInstanceCreated(
                            abilityInstanceId = mp.manaAbilityInstanceId,
                            affectorId = mp.landInstanceId,
                            sourceZoneId = ZoneIds.BATTLEFIELD,
                        ),
                    )
                    annotations.add(
                        AnnotationBuilder.tappedUntappedPermanent(
                            permanentId = mp.landInstanceId,
                            abilityId = mp.manaAbilityInstanceId,
                        ),
                    )
                    annotations.add(
                        AnnotationBuilder.userActionTaken(
                            instanceId = mp.manaAbilityInstanceId,
                            seatId = actingSeat,
                            actionType = 4,
                            abilityGrpId = mp.abilityGrpId,
                        ),
                    )
                    annotations.add(
                        AnnotationBuilder.manaPaid(
                            spellInstanceId = newId,
                            landInstanceId = mp.landInstanceId,
                            manaId = i + MANA_ID_BASE,
                            color = mp.color,
                        ),
                    )
                    annotations.add(
                        AnnotationBuilder.abilityInstanceDeleted(
                            abilityInstanceId = mp.manaAbilityInstanceId,
                            affectorId = mp.landInstanceId,
                        ),
                    )
                }
                val castActionType = if (transfer.isAdventureCast) 16 else 1
                annotations.add(AnnotationBuilder.userActionTaken(newId, actingSeat, actionType = castActionType))
            }
            TransferCategory.Resolve -> {
                annotations.add(AnnotationBuilder.resolutionStart(newId, grpId))
                annotations.add(AnnotationBuilder.resolutionComplete(newId, grpId))
                annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label, actingSeat))
            }
            TransferCategory.Sacrifice -> {
                if (transfer.manaPayments.isNotEmpty()) {
                    emitManaSacrificeBracket(annotations, transfer, actingSeat)
                } else {
                    if (origId != newId) annotations.add(AnnotationBuilder.objectIdChanged(origId, newId, affectorId))
                    annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label, affectorId = affectorId))
                }
            }
            TransferCategory.Destroy, TransferCategory.Countered,
            TransferCategory.Bounce, TransferCategory.Draw, TransferCategory.Discard,
            TransferCategory.Mill, TransferCategory.Surveil, TransferCategory.Exile,
            TransferCategory.Return, TransferCategory.Search, TransferCategory.Put,
            TransferCategory.SbaLegendRule, TransferCategory.SbaUnattachedAura,
            TransferCategory.ZoneTransfer,
            -> {
                if (origId != newId) {
                    annotations.add(AnnotationBuilder.objectIdChanged(origId, newId, affectorId))
                }
                annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label, affectorId = affectorId))
            }
        }

        // Persistent: EnteredZoneThisTurn for cards landing on battlefield or stack
        if (destZone == ZoneIds.BATTLEFIELD || destZone == ZoneIds.STACK) {
            persistent.add(AnnotationBuilder.enteredZoneThisTurn(destZone, newId))
        }

        // Persistent: ColorProduction for lands entering the battlefield
        if (category == TransferCategory.PlayLand && transfer.colorBitmasks.isNotEmpty()) {
            persistent.add(AnnotationBuilder.colorProduction(newId, transfer.colorBitmasks))
        }

        return annotations to persistent
    }

    /**
     * Emit the full mana-ability annotation bracket for a sacrifice-for-mana transfer.
     * Matches real server sequence: AbilityInstanceCreated → TappedUntapped →
     * ObjectIdChanged → ZoneTransfer(Sacrifice) → UserActionTaken(4) → ManaPaid →
     * AbilityInstanceDeleted.
     */
    private fun emitManaSacrificeBracket(
        annotations: MutableList<AnnotationInfo>,
        transfer: AppliedTransfer,
        actingSeat: Int,
    ) {
        val origId = transfer.origId
        val newId = transfer.newId
        for (mp in transfer.manaPayments) {
            annotations.add(AnnotationBuilder.abilityInstanceCreated(mp.manaAbilityInstanceId, origId, transfer.srcZoneId))
            annotations.add(AnnotationBuilder.tappedUntappedPermanent(origId, mp.manaAbilityInstanceId))
        }
        if (origId != newId) annotations.add(AnnotationBuilder.objectIdChanged(origId, newId))
        annotations.add(AnnotationBuilder.zoneTransfer(newId, transfer.srcZoneId, transfer.destZoneId, transfer.category.label))
        for ((i, mp) in transfer.manaPayments.withIndex()) {
            annotations.add(AnnotationBuilder.userActionTaken(mp.manaAbilityInstanceId, actingSeat, actionType = 4, abilityGrpId = mp.abilityGrpId))
            annotations.add(AnnotationBuilder.manaPaid(mp.spellInstanceId, origId, i + MANA_ID_BASE, mp.color))
            annotations.add(AnnotationBuilder.abilityInstanceDeleted(mp.manaAbilityInstanceId, origId))
        }
    }
}
