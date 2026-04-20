package leyline.game.annotations

import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.mapping.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

/**
 * Stage 2 of the annotation pipeline: generate annotations for zone transfers.
 *
 * Pure function — no bridge access, no side effects. Independently testable.
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
        actingSeat: SeatId,
    ): Pair<List<AnnotationInfo>, List<AnnotationInfo>> {
        val origId = InstanceId(transfer.origId)
        val newId = InstanceId(transfer.newId)
        val category = transfer.category
        val srcZone = transfer.srcZoneId
        val destZone = transfer.destZoneId
        val grpId = GrpId(transfer.grpId)
        val affectorId = if (transfer.affectorId != 0) InstanceId(transfer.affectorId) else null
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
                    emitManaTap(annotations, manaAbilityIid, landIid, ZoneIds.BATTLEFIELD)
                    emitManaConsume(annotations, i, mp, spellIid = newId, landIid = landIid, actingSeat = actingSeat)
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
     * ObjectIdChanged → ZoneTransfer(Sacrifice) → UserActionTaken(ActivateMana) → ManaPaid →
     * AbilityInstanceDeleted.
     *
     * Note: the tap-for-mana prelude runs first for every payment, then the ZT pair,
     * then the mana-consumed postlude per payment — the OIC+ZT is sandwiched between
     * the two halves (vs the CastSpell path, where OIC+ZT comes before the whole block).
     *
     * **Assumption:** the sacrificed object IS the mana source — we pass `origId` as
     * both the tap target and ManaPaid's `landInstanceId`, ignoring `mp.landInstanceId`.
     * Holds for Treasure / Clue / Blood tokens (self-sacrificing mana sources). Breaks
     * for effects where a non-source permanent is sacrificed as a cost and a *different*
     * permanent produces mana (e.g. Phyrexian Tower). No such call site exists today; if
     * one appears, use `InstanceId(mp.landInstanceId)` here.
     */
    private fun emitManaSacrificeBracket(
        annotations: MutableList<AnnotationInfo>,
        transfer: AppliedTransfer,
        actingSeat: SeatId,
    ) {
        val origId = InstanceId(transfer.origId)
        val newId = InstanceId(transfer.newId)
        for (mp in transfer.manaPayments) {
            emitManaTap(annotations, InstanceId(mp.manaAbilityInstanceId), origId, transfer.srcZoneId)
        }
        if (origId != newId) annotations.add(AnnotationBuilder.objectIdChanged(origId, newId))
        annotations.add(
            AnnotationBuilder.zoneTransfer(newId, transfer.srcZoneId, transfer.destZoneId, transfer.category.label),
        )
        for ((i, mp) in transfer.manaPayments.withIndex()) {
            emitManaConsume(annotations, i, mp, spellIid = InstanceId(mp.spellInstanceId), landIid = origId, actingSeat = actingSeat)
        }
    }

    /**
     * Tap-for-mana prelude: (AbilityInstanceCreated, TappedUntapped) for one land.
     * Used by both the CastSpell path and the Sacrifice path.
     */
    private fun emitManaTap(
        annotations: MutableList<AnnotationInfo>,
        manaAbilityIid: InstanceId,
        landIid: InstanceId,
        sourceZoneId: Int,
    ) {
        annotations.add(
            AnnotationBuilder.abilityInstanceCreated(
                abilityInstanceId = manaAbilityIid,
                affectorId = landIid,
                sourceZoneId = sourceZoneId,
            ),
        )
        annotations.add(
            AnnotationBuilder.tappedUntappedPermanent(permanentId = landIid, abilityId = manaAbilityIid),
        )
    }

    /**
     * Mana-consumed postlude: (UserActionTaken, ManaPaid, AbilityInstanceDeleted) for
     * one land's contribution to a spell or ability. [spellIid] is the consumer of
     * the mana, [landIid] is the producer.
     */
    private fun emitManaConsume(
        annotations: MutableList<AnnotationInfo>,
        index: Int,
        mp: ManaPaymentRecord,
        spellIid: InstanceId,
        landIid: InstanceId,
        actingSeat: SeatId,
    ) {
        val manaAbilityIid = InstanceId(mp.manaAbilityInstanceId)
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
                spellInstanceId = spellIid,
                landInstanceId = landIid,
                manaId = index + MANA_ID_BASE,
                color = mp.color,
            ),
        )
        annotations.add(AnnotationBuilder.abilityInstanceDeleted(manaAbilityIid, landIid))
    }
}
