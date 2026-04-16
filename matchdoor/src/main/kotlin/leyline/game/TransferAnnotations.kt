package leyline.game

import leyline.bridge.GrpId
import leyline.bridge.InstanceId
import leyline.bridge.SeatId
import leyline.game.mapper.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

/**
 * Stage 2 of the annotation pipeline: generate annotations for zone transfers.
 *
 * Pure function — no bridge access, no side effects. Independently testable.
 * Extracted from [AnnotationPipeline] for independent maintainability.
 */
object TransferAnnotations {

    /**
     * ManaPaid.id base value. Protocol uses sequential mana payment IDs
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
        val origId = InstanceId(transfer.origId)
        val newId = InstanceId(transfer.newId)
        val category = transfer.category
        val srcZone = transfer.srcZoneId
        val destZone = transfer.destZoneId
        val grpId = GrpId(transfer.grpId)
        val affectorId = InstanceId(transfer.affectorId)
        val actingSeat = SeatId(actingSeat)
        val altCostGrpId = GrpId(transfer.altCostAbilityGrpId)
        val annotations = mutableListOf<AnnotationInfo>()
        val persistent = mutableListOf<AnnotationInfo>()

        when (category) {
            TransferCategory.PlayLand -> {
                annotations.add(AnnotationBuilder.objectIdChanged(origId, newId))
                annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label))
                annotations.add(AnnotationBuilder.userActionTaken(newId, actingSeat, actionType = ActionType.Play_add3))
            }
            TransferCategory.CastSpell -> {
                annotations.add(AnnotationBuilder.objectIdChanged(origId, newId))
                annotations.add(AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label))
                // Per-land mana payment block (repeats for each land tapped)
                for ((i, mp) in transfer.manaPayments.withIndex()) {
                    val manaAbilityIid = InstanceId(mp.manaAbilityInstanceId)
                    val landIid = InstanceId(mp.landInstanceId)
                    annotations.add(
                        AnnotationBuilder.abilityInstanceCreated(
                            abilityInstanceId = manaAbilityIid,
                            affectorId = landIid,
                            sourceZoneId = ZoneIds.BATTLEFIELD,
                        ),
                    )
                    annotations.add(
                        AnnotationBuilder.tappedUntappedPermanent(
                            permanentId = landIid,
                            abilityId = manaAbilityIid,
                        ),
                    )
                    annotations.add(
                        AnnotationBuilder.userActionTaken(
                            instanceId = manaAbilityIid,
                            seatId = actingSeat,
                            actionType = ActionType.ActivateMana,
                            abilityGrpId = GrpId(mp.abilityGrpId),
                        ),
                    )
                    annotations.add(
                        AnnotationBuilder.manaPaid(
                            spellInstanceId = newId,
                            landInstanceId = landIid,
                            manaId = i + MANA_ID_BASE,
                            color = mp.color,
                        ),
                    )
                    annotations.add(
                        AnnotationBuilder.abilityInstanceDeleted(
                            abilityInstanceId = manaAbilityIid,
                            affectorId = landIid,
                        ),
                    )
                }
                val castActionType = if (transfer.isAdventureCast) ActionType.CastAdventure else ActionType.Cast
                annotations.add(
                    AnnotationBuilder.userActionTaken(
                        instanceId = newId,
                        seatId = actingSeat,
                        actionType = castActionType,
                        // Alt-cost casts (Madness, Flashback, Warp, Cycling, Impending)
                        // carry the alt-cost ability grpId on both abilityGrpId and
                        // alternativeGrpId, matching the client-visible wire shape.
                        abilityGrpId = altCostGrpId,
                        alternativeGrpId = altCostGrpId,
                    ),
                )
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
                    annotations.add(
                        AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label, affectorId = affectorId),
                    )
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
                annotations.add(
                    AnnotationBuilder.zoneTransfer(newId, srcZone, destZone, category.label, affectorId = affectorId),
                )
            }
        }

        // Persistent: EnteredZoneThisTurn for cards landing on battlefield or stack
        if (destZone == ZoneIds.BATTLEFIELD || destZone == ZoneIds.STACK) {
            persistent.add(AnnotationBuilder.enteredZoneThisTurn(destZone, newId))
        }

        // Persistent: CastingTimeOption for alt-cost casts (Madness, Flashback,
        // Warp, Cycling, Impending). Attached to the staged stack object; deleted via
        // diffDeletedPersistentAnnotationIds when the spell resolves or leaves the stack.
        if (category == TransferCategory.CastSpell && altCostGrpId.value != 0) {
            persistent.add(
                AnnotationBuilder.castingTimeOption(
                    stackInstanceId = newId,
                    type = CastingTimeOptionType.CastThroughAbility,
                    alternateCostGrpId = altCostGrpId,
                ),
            )
        }

        // Persistent: ColorProduction for lands entering the battlefield
        if (category == TransferCategory.PlayLand && transfer.colorOrdinals.isNotEmpty()) {
            persistent.add(AnnotationBuilder.colorProduction(newId, transfer.colorOrdinals))
        }

        return annotations to persistent
    }

    /**
     * Emit the full mana-ability annotation bracket for a sacrifice-for-mana transfer.
     * Matches expected client-facing sequence: AbilityInstanceCreated → TappedUntapped →
     * ObjectIdChanged → ZoneTransfer(Sacrifice) → UserActionTaken(4) → ManaPaid →
     * AbilityInstanceDeleted.
     */
    private fun emitManaSacrificeBracket(
        annotations: MutableList<AnnotationInfo>,
        transfer: AppliedTransfer,
        actingSeat: SeatId,
    ) {
        val origId = InstanceId(transfer.origId)
        val newId = InstanceId(transfer.newId)
        for (mp in transfer.manaPayments) {
            val manaAbilityIid = InstanceId(mp.manaAbilityInstanceId)
            annotations.add(
                AnnotationBuilder.abilityInstanceCreated(manaAbilityIid, origId, transfer.srcZoneId),
            )
            annotations.add(AnnotationBuilder.tappedUntappedPermanent(origId, manaAbilityIid))
        }
        if (origId != newId) annotations.add(AnnotationBuilder.objectIdChanged(origId, newId))
        annotations.add(
            AnnotationBuilder.zoneTransfer(newId, transfer.srcZoneId, transfer.destZoneId, transfer.category.label),
        )
        for ((i, mp) in transfer.manaPayments.withIndex()) {
            val manaAbilityIid = InstanceId(mp.manaAbilityInstanceId)
            annotations.add(
                AnnotationBuilder.userActionTaken(
                    manaAbilityIid,
                    actingSeat,
                    actionType = ActionType.ActivateMana,
                    abilityGrpId = GrpId(mp.abilityGrpId),
                ),
            )
            annotations.add(
                AnnotationBuilder.manaPaid(InstanceId(mp.spellInstanceId), origId, i + MANA_ID_BASE, mp.color),
            )
            annotations.add(AnnotationBuilder.abilityInstanceDeleted(manaAbilityIid, origId))
        }
    }
}
